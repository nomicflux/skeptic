(ns skeptic.worker.analyzer-cljs
  "Worker-side cljs analyzer execution. Mirrors the parse + reader-loop
   that live in `skeptic.cljs.analyzer-driver`, with no Skeptic / Schema /
   Malli dependency. The host-side wrappers
   (`parse-source-ns`/`analyze-form`/`analyze-source-file`) are rewired in
   Phase 8 to issue worker RPCs instead of running the cljs analyzer locally.

   The ns-ast for each parsed source file is held in a worker-scoped cache
   keyed by the source-file string. Wire payloads carry the source-file
   descriptor only; the cljs compiler state never crosses the wire."
  (:require [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.compiler]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private ns-ast-cache (atom {}))

(defn- empty-state
  []
  (let [state (ana-api/empty-state)]
    (swap! state assoc-in [:options :spec-skip-macros] true)
    state))

(defn- walk-ast
  [f ast]
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

(defn- normalize-cljs-node
  [n]
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

(defn- strip-cljs-type
  [ast]
  (walk-ast normalize-cljs-node ast))

(defn parse-ns
  "Parse the `(ns ...)` form of `source-file` and cache the resulting ns AST
   under the source-file string. Returns the source-file string (the key the
   host will pass back on subsequent ops)."
  [source-file]
  (let [path (str source-file)]
    (when-not (contains? @ns-ast-cache path)
      (let [ns-ast (ana-api/with-state (empty-state)
                     (:ast (ana-api/parse-ns (io/file source-file)
                                             {:load-macros true :analyze-deps false})))]
        (swap! ns-ast-cache assoc path ns-ast)))
    path))

(defn analyze-form-by-ns-key
  "Analyze `form` using the cached ns-ast for `ns-key` (the source-file
   string returned by `parse-ns`). Returns a stripped cljs AST."
  [ns-key form]
  (let [ns-ast (get @ns-ast-cache ns-key)]
    (ana-api/no-warn
     (-> (ana-api/analyze (assoc (ana-api/empty-env) :ns ns-ast)
                          form
                          (:name ns-ast))
         strip-cljs-type))))

(defn- analyze-source-entry
  [state base-env source-form]
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

(defn- analyze-source-entry-result
  [state base-env ns-ast source-form]
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

(defn analyze-source-file
  "Analyze every top-level form of `source-file` using the cljs analyzer's
   reader loop. Returns `{:ns-ast :entries :asts}` in the shape the host
   wrapper produces today."
  [source-file]
  (let [path (str source-file)
        state (empty-state)]
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
                      ns-form? (and (seq? source-form) (= 'ns (first source-form)))]
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
                 :asts (filterv some? (mapv :ast entries))}))))))))
