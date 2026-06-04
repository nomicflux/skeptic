(ns skeptic.worker.analyzer-cljs
  "Worker-side cljs analyzer execution. Mirrors the parse + reader-loop
   that live in `skeptic.cljs.analyzer-driver`, with no Skeptic / Schema /
   Malli dependency. The host-side source-file wrapper is rewired to issue a
   worker RPC instead of running the cljs analyzer locally.

   Wire payloads carry the source-file descriptor only; the cljs compiler state
   never crosses the wire."
  (:require [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.compiler]
            [clojure.java.io :as io]))

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

(defn- ast-node?
  [v]
  (and (map? v) (contains? v :op)))

(defn- back-ref-slot?
  "A slot is a back-ref iff it is NOT one of the node's structural `:children`
   yet its value is an AST node (or a vector of AST nodes). Such a node sits off
   the `:children` spine `walk-ast` descends, so its own `:env` is never
   stripped and any plain-graph reader (`edn-safe?`, `pr-str`) runs away into
   the analyzer environment. Detection is one level deep — it never looks
   *inside* the back-ref node, so it cannot itself run into that `:env`.
   Examples: `:local`→`:init`, `:fn`→`:loop-lets`, `:binding`→`:method-params`."
  [children k v]
  (and (not (contains? children k))
       (not= :meta k)
       (or (ast-node? v)
           (and (vector? v) (seq v) (every? ast-node? v)))))

(defn- normalize-cljs-node
  [n]
  (let [children (set (:children n))
        ;; Drop :type/:env here; drop EVERY non-:children back-ref slot
        ;; structurally (not by name) so no AST node carrying :env survives off
        ;; the spine walk-ast/handle-project-node descend. :meta is exempt:
        ;; walk-ast/handle-project-node project it when it is an AST child.
        n (reduce-kv (fn [acc k v] (if (back-ref-slot? children k v) (dissoc acc k) acc))
                     (dissoc n :type :env)
                     n)
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

(defn- analyze-source-entry
  [state base-env source-form]
  (let [env (assoc base-env
                   :ns (or (ana-api/find-ns state (ana-api/current-ns))
                           (:ns base-env)))]
    (ana-api/no-warn
     (-> (ana-api/analyze state env source-form nil {})
         strip-cljs-type))))

(defn- analyze-source-entry-result
  "Analyze one top-level form, isolating any analyzer throw as an `:exception`
   entry so a single bad form does not abort the namespace (the host turns it
   into an expression-phase exception finding). Dependencies are analyzed in
   full (`*analyze-deps* true`), so referenced vars resolve the way they do in
   a real compile and no per-form repair is needed."
  [state base-env source-form]
  (try
    {:ast (analyze-source-entry state base-env source-form)}
    (catch Throwable e
      {:ast nil :exception e})))

(defn- with-analysis-bindings
  "Run `f` with the cljs analyzer dynamic vars Skeptic uses bound, inside a
   fresh `empty-state`. `*load-macros*` is true so `:require-macros` deps
   load on this (worker) JVM under the project basis."
  [path f]
  (let [state (empty-state)]
    (ana-api/with-state state
      (binding [ana/*file-defs* (atom #{})
                ana/*unchecked-if* false
                ana/*unchecked-arrays* false
                ana/*analyze-deps* true
                ana/*load-macros* true
                ana/*cljs-ns* 'cljs.user
                ana/*cljs-file* path]
        (f state)))))

(defn- analyze-ns-form
  "Read `source-file`'s leading `(ns ...)` form and analyze it to an ns-AST.
   Throws if the file has no ns form."
  [state path source-file]
  (with-open [r (io/reader source-file)]
    (let [base-env (assoc (ana-api/empty-env) :build-options {})
          ns-form (some (fn [form]
                          (when (and (seq? form) (= 'ns (first form))) form))
                        (ana-api/forms-seq r path))]
      (if ns-form
        (analyze-source-entry state base-env ns-form)
        (throw (ex-info "cljs source has no (ns ...) form" {:source-file path}))))))

(defn ns-head
  "Parse only `source-file`'s `(ns ...)` form on the worker (project basis) and
   return the dependency-ordering head fields. The rest of the file is not
   analyzed. Mirrors the data `skeptic.cljs.topo/file-head` needs."
  [source-file]
  (let [path (str source-file)
        ns-ast (with-analysis-bindings path
                 (fn [state] (analyze-ns-form state path source-file)))]
    (select-keys ns-ast [:name :requires :require-macros :use-macros])))

(defn analyze-source-file
  "Analyze every top-level form of `source-file` using the cljs analyzer's
   reader loop. Returns `{:ns-ast :entries :asts}` in the shape the host
   wrapper produces today."
  [source-file]
  (let [path (str source-file)]
    (with-analysis-bindings path
      (fn [state]
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
                                                              source-form)]
                      (recur (next s)
                             ns-ast
                             (conj entries (merge {:source-form source-form} result))))))
                {:ns-ast (or ns-ast
                             (throw (ex-info "cljs source has no (ns ...) form"
                                             {:source-file path})))
                 :entries entries
                 :asts (filterv some? (mapv :ast entries))}))))))))
