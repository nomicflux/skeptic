(ns skeptic.cljs.analyzer-driver
  "Single-form cljs analysis entrypoints via a file-local cljs compiler state.

  `analyze-form` analyzes one already-read cljs form against a supplied ns AST
  inside a non-leaking compiler state, used by the schema/malli collectors to
  type a post-macroexpansion declaration body. Whole-file analysis lives on the
  worker (`skeptic.worker.analyzer-cljs`); the checker never analyzes cljs
  source files through this namespace.

  cljs ASTs carry `:type` on `:binding`/`:fn-method` nodes that conflicts
  with skeptic's `:type` slot (SemanticType), and `:binding` nodes lack
  `:form`. `analyze-form` strips and synthesizes via `normalize-cljs-node`
  so the skeptic annotate pipeline starts from a clean shape."
  (:require [schema.core :as s]
            [skeptic.classloader-fix]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.cljs.analyzer-driver.schema :as ads]))

(defonce ^:private ensure-cljs-loaded!
  (delay
    (require 'cljs.env)
    (require 'cljs.analyzer)
    (require 'cljs.analyzer.api)
    (require 'cljs.compiler)
    true))

(defn- resolved-cljs-vars
  []
  @ensure-cljs-loaded!
  {:*cljs-warnings* (resolve 'cljs.analyzer/*cljs-warnings*)
   :empty-state     (resolve 'cljs.analyzer.api/empty-state)
   :empty-env       (resolve 'cljs.analyzer.api/empty-env)
   :analyze         (resolve 'cljs.analyzer.api/analyze)})

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
  (let [state (@(:empty-state (resolved-cljs-vars)))]
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
  (let [{:keys [*cljs-warnings* empty-env analyze]} (resolved-cljs-vars)
        no-warnings (zipmap (keys @*cljs-warnings*) (repeat false))]
    (with-bindings* {*cljs-warnings* no-warnings}
      (fn []
        (-> (@analyze (assoc (@empty-env) :ns ns-ast)
                     form
                     (:name ns-ast))
            strip-cljs-type)))))
