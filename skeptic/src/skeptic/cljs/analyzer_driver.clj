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
            [clojure.string :as str]
            [clojure.walk :as walk]))

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

(s/defn ^:private parse-source-ns* :- aas/AnnotatedNode
  [source-file :- s/Any
   load-macros? :- s/Bool]
  (let [path (str source-file)]
    (ana-api/with-state (empty-state)
      (binding [ana/*analyze-deps* false
                ana/*load-macros* load-macros?
                ana/*cljs-ns* 'cljs.user
                ana/*cljs-file* path]
        (:ast (ana-api/parse-ns source-file
                                {:load-macros load-macros?
                                 :analyze-deps false}))))))

(s/defn parse-source-ns :- aas/AnnotatedNode
  "Parse a `.cljs` / `.cljc` source file's `(ns ...)` form. Triggers JVM
  loading of any `:require-macros` namespaces and returns the analyzed ns
  AST: a map with `:name`, `:requires`, `:require-macros`, `:uses`, etc.
  Suitable for use as the `:ns` slot of an analysis env passed to
  `analyze-form`. Wraps the call in a Skeptic-configured empty state so
  cljs macro-syntax spec validation is skipped; the state is discarded
  on return, while JVM-loaded macro namespaces persist."
  [source-file :- s/Any]
  (parse-source-ns* source-file true))

(s/defn parse-source-ns-head :- aas/AnnotatedNode
  "Parse the `(ns ...)` form for dependency ordering. This uses the cljs
  analyzer's namespace parser and cljs reader context, but does not load
  macros because topo sorting needs only `:requires` / `:require-macros`
  metadata. Full source-file analysis still loads macros through the analyzer."
  [source-file :- s/Any]
  (parse-source-ns* source-file false))

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

(defn- explicit-var-symbol
  [form]
  (when (and (seq? form)
             (= 'var (first form))
             (nil? (nnext form)))
    (let [sym (second form)]
      (when (symbol? sym) sym))))

(defn- explicit-var-symbols
  [source-form]
  (keep explicit-var-symbol
        (tree-seq coll? form-children source-form)))

(def ^:private cljs-core-explicit-var-refs
  '#{identity})

(defn- required-namespace
  [ns-ast qualifier]
  (or (get (:requires ns-ast) qualifier)
      (get (:require-macros ns-ast) qualifier)
      (when (= 'cljs.core qualifier) 'cljs.core)
      (when (some #{qualifier} (concat (vals (:requires ns-ast))
                                       (vals (:require-macros ns-ast))))
        qualifier)))

(defn- required-var-symbol
  [ns-ast var-sym]
  (if-let [qualifier (some-> var-sym namespace symbol)]
    (when-let [ns-sym (required-namespace ns-ast qualifier)]
      (symbol (str ns-sym) (name var-sym)))
    (when (contains? cljs-core-explicit-var-refs var-sym)
      (symbol "cljs.core" (name var-sym)))))

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

(def ^:private synthetic-external-ns
  'skeptic.synthetic.cljs)

(defn- synthetic-external-var-symbol
  [qualified-var-sym]
  (symbol (name synthetic-external-ns) (name qualified-var-sym)))

(def ^:private known-harness-vars
  '#{cljs.spec.test.alpha/instrument
     cljs.spec.test.alpha/unstrument
     doo.runner/doo-tests})

(defn- referred-macro-symbol
  [ns-ast source-sym]
  (when-let [target (or (get (:use-macros ns-ast) source-sym)
                        (get (:uses-macros ns-ast) source-sym))]
    (if (namespace target)
      target
      (symbol (str target) (name source-sym)))))

(defn- ns-ast-requires-namespace?
  [ns-ast ns-sym]
  (letfn [(contains-ns? [x]
            (cond
              (= x ns-sym) true
              (map? x) (or (some contains-ns? (keys x))
                           (some contains-ns? (vals x)))
              (coll? x) (some contains-ns? x)
              :else false))]
    (boolean
     (some contains-ns?
           (vals (select-keys ns-ast [:requires :require-macros :uses
                                      :use-macros :uses-macros]))))))

(def ^:private known-harness-vars-by-name
  (group-by (comp symbol name) known-harness-vars))

(defn- required-known-harness-symbol
  [ns-ast source-sym]
  (some (fn [qualified-sym]
          (when (ns-ast-requires-namespace? ns-ast (symbol (namespace qualified-sym)))
            qualified-sym))
        (get known-harness-vars-by-name (symbol (name source-sym)))))

(defn- qualified-source-symbol
  [ns-ast source-sym]
  (when (symbol? source-sym)
    (or (when (namespace source-sym)
          (required-var-symbol ns-ast source-sym))
        (referred-macro-symbol ns-ast source-sym)
        (required-known-harness-symbol ns-ast source-sym))))

(defn- known-harness-symbol
  [ns-ast source-sym]
  (let [qualified-sym (qualified-source-symbol ns-ast source-sym)]
    (when (contains? known-harness-vars qualified-sym)
      qualified-sym)))

(defn- rewrite-known-harness-forms
  [state ns-ast source-form]
  (let [rewrote? (atom false)
        repaired (walk/postwalk
                  (fn [form]
                    (if (seq? form)
                      (if-let [qualified-sym (known-harness-symbol ns-ast (first form))]
                        (let [replacement-sym (synthetic-external-var-symbol qualified-sym)]
                          (reset! rewrote? true)
                          (seed-cljs-var-def! state replacement-sym)
                          (with-meta (cons replacement-sym (rest form)) (meta form)))
                        form)
                      form))
                  source-form)]
    (when @rewrote?
      repaired)))

(defn- seed-explicit-var-refs!
  [state ns-ast source-form]
  (reduce (fn [seeded? source-var-sym]
            (let [qualified-var-sym (required-var-symbol ns-ast source-var-sym)
                  inserted? (boolean
                             (and qualified-var-sym
                                  (seed-cljs-var-def! state qualified-var-sym)))]
              (or seeded? inserted?)))
          false
          (explicit-var-symbols source-form)))

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

(defn- unresolved-var-symbol
  [e]
  (some (fn [t]
          (let [data (ex-data t)
                message (.getMessage ^Throwable t)]
            (when (= :cljs/analysis-error (:tag data))
              (or (:var data)
                  (some->> (re-find #"Unable to resolve var: ([^\s]+)" (or message ""))
                           second
                           symbol)))))
        (throwable-chain e)))

(defn- macroexpansion-qualified-var-symbol
  [e]
  (some (fn [t]
          (let [data (ex-data t)
                phase (or (:phase data) (:clojure.error/phase data))
                sym (or (:symbol data) (:clojure.error/symbol data))]
            (when (and (= :macroexpansion phase)
                       (qualified-symbol? sym))
              sym)))
        (throwable-chain e)))

(defn- matching-macro-operator?
  [op qualified-var-sym]
  (and (symbol? op)
       (or (= op qualified-var-sym)
           (= (name op) (name qualified-var-sym)))))

(defn- rewrite-macro-operator
  [source-form qualified-var-sym replacement-sym]
  (walk/postwalk
   (fn [form]
     (if (and (seq? form)
              (matching-macro-operator? (first form) qualified-var-sym))
       (with-meta (cons replacement-sym (rest form)) (meta form))
       form))
   source-form))

(defn- repair-macroexpansion-source-form!
  [state source-form e]
  (when-let [qualified-var-sym (macroexpansion-qualified-var-symbol e)]
    (let [replacement-sym (synthetic-external-var-symbol qualified-var-sym)
          repaired-form (rewrite-macro-operator source-form qualified-var-sym replacement-sym)]
      (when-not (= repaired-form source-form)
        (seed-cljs-var-def! state replacement-sym)
        repaired-form))))

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

(defn- ns-resource-path
  [ns-sym suffix]
  (str (-> (str ns-sym)
           (str/replace "." "/")
           (str/replace "-" "_"))
       suffix))

(defn- source-for-ns
  [ns-sym]
  (or (io/resource (ns-resource-path ns-sym ".cljs"))
      (io/resource (ns-resource-path ns-sym ".cljc"))))

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

(defn- quoted-symbol
  [form]
  (when (and (seq? form)
             (= 'quote (first form))
             (nil? (nnext form))
             (symbol? (second form)))
    (second form)))

(defn- doo-tests-form?
  [ns-ast form]
  (and (seq? form)
       (= 'doo.runner/doo-tests (known-harness-symbol ns-ast (first form)))))

(defn- seed-doo-test-namespaces!
  [state ns-ast source-form]
  (reduce
   (fn [loaded? form]
     (if (doo-tests-form? ns-ast form)
       (reduce (fn [inner-loaded? ns-sym]
                 (or (analyze-missing-namespace! state ns-sym)
                     inner-loaded?))
               loaded?
               (keep quoted-symbol (rest form)))
       loaded?))
   false
   (filter seq? (tree-seq coll? form-children source-form))))

(defn- prepare-known-harness-analysis!
  [state ns-ast source-form]
  (seed-doo-test-namespaces! state ns-ast source-form))

(defn- repair-unresolved-required-var!
  [state ns-ast e]
  (when-let [source-var-sym (unresolved-var-symbol e)]
    (when-let [qualified-var-sym (required-var-symbol ns-ast source-var-sym)]
      (or (analyze-missing-namespace! state (symbol (namespace qualified-var-sym)))
          (seed-cljs-var-def! state qualified-var-sym)))))

(defn- repair-analysis-error!
  [state ns-ast source-form e]
  (or (when-let [ns-sym (missing-analyzer-ns e)]
        (analyze-missing-namespace! state ns-sym))
      (and ns-ast
           (unresolved-var-analysis-error? e)
           (or (repair-unresolved-required-var! state ns-ast e)
               (seed-explicit-var-refs! state ns-ast source-form)))))

(s/defn ^:private analyze-source-entry-result :- s/Any
  [state       :- s/Any
   base-env    :- s/Any
   ns-ast      :- (s/maybe aas/AnnotatedNode)
   source-form :- s/Any]
  (loop [remaining-repairs 8
         analysis-form source-form]
    (let [result (try
                   (when ns-ast
                     (prepare-known-harness-analysis! state ns-ast analysis-form))
                   {:ast (analyze-source-entry state base-env analysis-form)}
                   (catch Throwable e
                     {:ast nil :exception e}))]
      (if-let [e (:exception result)]
        (if (pos? remaining-repairs)
          (if-let [repaired-form (repair-macroexpansion-source-form! state analysis-form e)]
            (recur (dec remaining-repairs) repaired-form)
            (if-let [repaired-form (and ns-ast
                                        (rewrite-known-harness-forms state ns-ast analysis-form))]
              (recur (dec remaining-repairs) repaired-form)
              (if (repair-analysis-error! state ns-ast analysis-form e)
                (recur (dec remaining-repairs) analysis-form)
                result)))
          result)
        result))))

(s/defn ^:private analyze-ns-source-entry :- aas/AnnotatedNode
  [state       :- s/Any
   base-env    :- s/Any
   source-form :- s/Any]
  (try
    (analyze-source-entry state base-env source-form)
    (catch Throwable _e
      (binding [ana/*load-macros* false]
        (analyze-source-entry state base-env source-form)))))

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
                     (let [ns-ast (analyze-ns-source-entry state base-env source-form)]
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
