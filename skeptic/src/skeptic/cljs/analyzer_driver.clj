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
            [clojure.java.io :as io]))

(s/defn ^:private normalize-cljs-node :- ads/RawCljsAst
  [n :- ads/RawCljsAst]
  (let [n (dissoc n :type)]
    (if (and (= :binding (:op n)) (not (contains? n :form)))
      (assoc n :form (:name n))
      n)))

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

(s/defn parse-source-ns :- aas/AnnotatedNode
  "Parse a `.cljs` / `.cljc` source file's `(ns ...)` form. Triggers JVM
  loading of any `:require-macros` namespaces and returns the analyzed ns
  AST: a map with `:name`, `:requires`, `:require-macros`, `:uses`, etc.
  Suitable for use as the `:ns` slot of an analysis env passed to
  `analyze-form`. Discards the ephemeral compiler state parse-ns auto-
  creates internally; the JVM-loaded macro namespaces persist."
  [source-file :- s/Any]
  (:ast (ana-api/parse-ns source-file
                          {:load-macros true :analyze-deps false})))

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

(s/defn analyze-source-file :- ads/SourceFileAnalysis
  "Analyze every top-level form of a cljs/cljc source file using the cljs
  analyzer's reader loop. Returns `{:ns-ast ns-ast
  :entries [{:source-form form :ast ast} ...] :asts [ast ...]}`. `:entries`
  preserves the source-form/AST pairing needed by checker reporting; `:asts`
  remains for collector compatibility."
  [source-file :- s/Any]
  (let [state (ana-api/empty-state)
        path  (str source-file)]
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
                      ast (analyze-source-entry state base-env source-form)]
                  (if (= :ns (:op ast))
                    (recur (next s) ast entries)
                    (recur (next s) ns-ast (conj entries {:source-form source-form
                                                          :ast ast}))))
                {:ns-ast (or ns-ast
                             (throw (ex-info "cljs source has no (ns ...) form"
                                             {:source-file path})))
                 :entries entries
                 :asts (mapv :ast entries)}))))))))
