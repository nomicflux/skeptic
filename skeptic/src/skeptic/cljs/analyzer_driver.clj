(ns skeptic.cljs.analyzer-driver
  "cljs analyzer entrypoints via a file-local cljs compiler state.

  Each cljs source file is read and analyzed inside one non-leaking compiler
  state. This lets `cljs.analyzer.api/forms-seq` read with the analyzer's
  current cljs namespace, cljs data readers, and alias map, then immediately
  analyzes each top-level form against the same state. The file pass loads
  macros but does not analyze required cljs dependencies; the state is local
  to the source file and is not threaded through the checker.

  cljs ASTs carry `:type` on `:binding`/`:fn-method` nodes that conflicts
  with skeptic's `:type` slot (SemanticType), and `:binding` nodes lack
  `:form`. `analyze-form` strips and synthesizes via `normalize-cljs-node`
  so the skeptic annotate pipeline starts from a clean shape."
  (:require [schema.core :as s]
            [skeptic.classloader-fix]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.cljs.analyzer-driver.schema :as ads]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.compiler]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(s/defn ^:private normalize-cljs-node :- ads/RawCljsAst
  [n :- ads/RawCljsAst]
  (let [n (dissoc n :type :env)
        n (cond
            (not (contains? n :info)) n
            (= :var (:op n))          (update n :info select-keys [:name :meta])
            :else                     (dissoc n :info))
        n (if (and (= :fn (:op n)) (map? (:name n)))
            (assoc n :name (or (:form (:name n)) (:name (:name n))))
            n)]
    (if (and (= :binding (:op n)) (not (contains? n :form)))
      (assoc n :form (:name n))
      n)))

(defn empty-state
  "Empty cljs compiler state with `:spec-skip-macros true`. Skeptic
   does not validate cljs macro-syntax specs
   (e.g. `:cljs.core.specs.alpha/ns-form`); those specs bind Skeptic to
   whichever `cljs.core.specs.alpha` shipped with the bundled cljs
   version, breaking on projects that use ns-clauses (`:refer-global`,
   etc.) newer than the bundled spec knows. The analyzer's own 'ns
   parser handles these clauses via `ns-spec-cases`; only the spec
   validator was out-of-date. `do-macroexpand-check`
   (`analyzer.cljc:4252`) honors this option."
  []
  (let [state (ana-api/empty-state)]
    (swap! state assoc-in [:options :spec-skip-macros] true)
    state))

(s/defn ^:private walk-ast :- ads/RawCljsAst
  "Directed AST walker: applies f to the node, then recurses only through
  the keys named in `:children`. Avoids `:env`, `:info`, `:meta`, and other
  non-AST slots that cause `clojure.walk/prewalk` to OOM (cycles + large
  binding-info trees) on real cljs ASTs."
  [f
   ast :- ads/RawCljsAst]
  (let [ast' (f ast)]
    (reduce (fn [a k]
              (let [v (get a k)]
                (cond
                  (and (map? v) (contains? v :op))
                  (assoc a k (walk-ast f v))
                  (and (vector? v) (seq v) (every? #(and (map? %) (contains? % :op)) v))
                  (assoc a k (mapv #(walk-ast f %) v))
                  :else a)))
            ast'
            (:children ast'))))

(s/defn ^:private strip-cljs-type :- aas/AnnotatedNode
  [ast :- ads/RawCljsAst]
  (walk-ast normalize-cljs-node ast))

(defn- find-by-op*
  [op ast]
  (when (and (map? ast) (contains? ast :op))
    (if (= op (:op ast))
      ast
      (some (fn [k]
              (let [v (get ast k)]
                (cond
                  (and (map? v) (contains? v :op))
                  (find-by-op* op v)
                  (and (vector? v) (seq v) (every? #(and (map? %) (contains? % :op)) v))
                  (some #(find-by-op* op %) v))))
            (:children ast)))))

(s/defn find-by-op :- (s/maybe aas/AnnotatedNode)
  "Return the first AST node whose `:op` is `op`, located by directed descent
  through `:children` keys only. Mirrors `walk-ast`'s pruning so callers do
  not traverse `:env`, `:info`, `:meta`, or other non-AST slots that can
  carry compiler-state cycles or large binding-info trees on real cljs ASTs."
  [op  :- s/Keyword
   ast :- aas/AnnotatedNode]
  (find-by-op* op ast))

(s/defn analyze-form :- aas/AnnotatedNode
  "Analyze an already-read cljs form using the supplied ns AST. Real source
  files should use `analyze-source-file`, which keeps cljs reading and
  analysis in one file-local compiler state."
  [ns-ast :- aas/AnnotatedNode
   form   :- s/Any]
  (ana-api/no-warn
   (-> (ana-api/analyze (assoc (ana-api/empty-env) :ns ns-ast)
                        form
                        (:name ns-ast))
       strip-cljs-type)))

(s/defn ^:private analyze-source-entry :- aas/AnnotatedNode
  [state       :- s/Any
   base-env    :- s/Any
   source-form :- s/Any]
  (let [env (assoc base-env
                   :ns (or (ana-api/find-ns state (ana-api/current-ns))
                           (:ns base-env)))]
    (ana-api/no-warn
     (-> (ana-api/analyze state env source-form nil {})
         strip-cljs-type))))

(defn- form-children
  [form]
  (cond
    (map? form) (mapcat identity form)
    (coll? form) (seq form)
    :else nil))

(defn- explicit-qualified-var-symbol
  [form]
  (when (and (seq? form)
             (= 'var (first form))
             (nil? (nnext form)))
    (let [sym (second form)]
      (when (and (symbol? sym) (namespace sym))
        sym))))

(defn- explicit-qualified-var-symbols
  [source-form]
  (keep explicit-qualified-var-symbol
        (tree-seq coll? form-children source-form)))

(defn- required-var-symbol
  [ns-ast var-sym]
  (when-let [qualifier (some-> var-sym namespace symbol)]
    (when-let [ns-sym (or (get (:requires ns-ast) qualifier)
                          (get (:require-macros ns-ast) qualifier)
                          (when (= 'cljs.core qualifier) 'cljs.core))]
      (symbol (str ns-sym) (name var-sym)))))

(defn- seed-cljs-var-def!
  [state qualified-var-sym]
  (let [ns-sym (symbol (namespace qualified-var-sym))
        var-sym (symbol (name qualified-var-sym))
        inserted? (atom false)]
    (swap! state update-in [:cljs.analyzer/namespaces ns-sym]
           (fn [ns-entry]
             (let [ns-entry (assoc (or ns-entry {}) :name (or (:name ns-entry) ns-sym))]
               (if (contains? (:defs ns-entry) var-sym)
                 ns-entry
                 (do
                   (reset! inserted? true)
                   (assoc-in ns-entry [:defs var-sym]
                             {:name qualified-var-sym
                              :ns ns-sym
                              :meta {:skeptic.synthetic/external-var true}}))))))
    @inserted?))

(defn- seed-explicit-var-refs!
  [state ns-ast source-form]
  (reduce (fn [seeded? source-var-sym]
            (let [qualified-var-sym (required-var-symbol ns-ast source-var-sym)
                  inserted? (boolean
                             (and qualified-var-sym
                                  (seed-cljs-var-def! state qualified-var-sym)))]
              (or seeded? inserted?)))
          false
          (explicit-qualified-var-symbols source-form)))

(defn- throwable-chain
  [e]
  (take-while some? (iterate #(.getCause ^Throwable %) e)))

(defn- unresolved-var-analysis-error?
  [e]
  (boolean
   (some (fn [t]
           (let [data (ex-data t)
                 message (.getMessage ^Throwable t)]
             (and (= :cljs/analysis-error (:tag data))
                  (str/includes? (or message "") "Unable to resolve var: "))))
         (throwable-chain e))))

(defn- missing-analyzer-ns
  [e]
  (some (fn [t]
          (let [data (ex-data t)
                message (.getMessage ^Throwable t)
                ns-sym (:ns data)]
            (when (and (symbol? ns-sym)
                       (str/includes? (or message "") "No namespace found: "))
              ns-sym)))
        (throwable-chain e)))

(defn- source-for-ns
  [ns-sym]
  (or (io/resource (str (str/replace (str ns-sym) "." "/") ".cljs"))
      (io/resource (str (str/replace (str ns-sym) "." "/") ".cljc"))))

(defn- analyze-missing-namespace!
  [state ns-sym]
  (when-not (ana-api/find-ns state ns-sym)
    (when-let [source (source-for-ns ns-sym)]
      (ana-api/no-warn
       (binding [ana/*analyze-deps* false
                 ana/*load-macros* true]
         (ana-api/analyze-file state source {:cache-analysis false
                                             :spec-skip-macros true})))
      true)))

(defn- repair-analysis-error!
  [state ns-ast source-form e]
  (or (when-let [ns-sym (missing-analyzer-ns e)]
        (analyze-missing-namespace! state ns-sym))
      (and ns-ast
           (unresolved-var-analysis-error? e)
           (seed-explicit-var-refs! state ns-ast source-form))))

(s/defn ^:private analyze-source-entry-result :- s/Any
  [state       :- s/Any
   base-env    :- s/Any
   ns-ast      :- (s/maybe aas/AnnotatedNode)
   source-form :- s/Any]
  (loop [remaining-repairs 8]
    (let [result (try
                   {:ast (analyze-source-entry state base-env source-form)}
                   (catch Throwable e
                     {:ast nil :exception e}))]
      (if-let [e (:exception result)]
        (if (and (pos? remaining-repairs)
                 (repair-analysis-error! state ns-ast source-form e))
          (recur (dec remaining-repairs))
          result)
        result))))

(s/defn analyze-source-file :- ads/SourceFileAnalysis
  "Analyze every top-level form of a cljs/cljc source file using the cljs
  analyzer's reader loop. Returns `{:ns-ast ns-ast
  :entries [{:source-form form :ast ast} ...] :asts [ast ...]}`. `:entries`
  preserves the source-form/AST pairing needed by checker reporting; `:asts`
  remains for collector compatibility.

  Arity-1 creates a fresh, file-local compiler state — analyses are
  isolated. Arity-2 takes a caller-supplied state so callers driving a
  multi-file pass can share `[::namespaces]` across analyses; macros that
  introspect earlier-parsed nss at expansion time then find them."
  ([source-file :- s/Any]
   (analyze-source-file (empty-state) source-file))
  ([state       :- s/Any
    source-file :- s/Any]
   (let [path (str source-file)]
     (ana-api/with-state state
       (binding [ana/*file-defs* (atom #{})
                 ana/*unchecked-if* false
                 ana/*unchecked-arrays* false
                 ana/*analyze-deps* false
                 ana/*load-macros* true
                 ana/*cljs-ns* 'cljs.user
                 ana/*cljs-file* path]
         (with-open [r (io/reader source-file)]
           (let [base-env (assoc (ana-api/empty-env) :build-options {})]
             (loop [forms (ana-api/forms-seq r path)
                    ns-ast nil
                    entries []]
               (if-let [s (seq forms)]
                 (let [source-form (first s)
                       ns-form?    (and (seq? source-form) (= 'ns (first source-form)))]
                   (if ns-form?
                     (let [ns-ast (analyze-source-entry state base-env source-form)]
                       (recur (next s) ns-ast entries))
                     (let [result (analyze-source-entry-result state
                                                               (assoc base-env :ns ns-ast)
                                                               ns-ast
                                                               source-form)]
                       (recur (next s)
                              ns-ast
                              (conj entries (merge {:source-form source-form} result))))))
                 {:ns-ast (or ns-ast
                              (throw (ex-info "cljs source has no (ns ...) form"
                                              {:source-file path})))
                  :entries entries
                  :asts (filterv some? (mapv :ast entries))})))))))))
