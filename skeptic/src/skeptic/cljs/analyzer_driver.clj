(ns skeptic.cljs.analyzer-driver
  "Single-form cljs analyzer entrypoint. The compiler-env passed in must be
  bootstrapped via `skeptic.cljs.compiler-env/fresh-state`.

  cljs ASTs carry a `:type` field on `:binding` (`:let|:loop|:arg|...`) and
  similar nodes that conflicts with skeptic's `:type` slot (SemanticType).
  `analyze-form` strips `:type` recursively before returning so the skeptic
  annotate pipeline starts from a clean slate, mirroring `normalize-raw-ast`
  on the JVM side."
  (:require [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.env :as env]))

(defn- ns-info
  [cenv ns-sym]
  (or (get-in @cenv [::ana/namespaces ns-sym])
      {:name ns-sym}))

(defn- normalize-cljs-node
  "Per-node fixups bridging cljs AST shape to skeptic's JVM-shaped expectations:
  - dissoc `:type`   — cljs uses `:type` on `:binding` etc. for the binding kind
                       (`:let|:loop|:arg|...`); collides with skeptic's
                       SemanticType slot.
  - synthesize :form — cljs `:binding` nodes carry `:name` but no `:form`;
                       skeptic reads `:form` via `aapi/node-form` and rejects
                       missing-required-key."
  [n]
  (let [n (dissoc n :type)]
    (if (and (= :binding (:op n)) (not (contains? n :form)))
      (assoc n :form (:name n))
      n)))

(defn- ast-children-keys
  "cljs analyzer nodes carry a `:children` vector listing keys whose values
  are child AST nodes (or vectors of child AST nodes). Falls back to nil
  for nodes without `:children` (leaves)."
  [n]
  (:children n))

(defn- walk-ast
  "Directed AST walker: applies f to the node, then recurses only through
  the keys named in `:children`. Avoids `:env`, `:info`, `:meta`, and other
  non-AST slots that cause `clojure.walk/prewalk` to OOM (cycles + large
  binding-info trees) on real cljs ASTs."
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
            (ast-children-keys ast'))))

(defn- strip-cljs-type
  [ast]
  (walk-ast normalize-cljs-node ast))

(defn analyze-form
  [cenv ns-sym form]
  (env/with-compiler-env cenv
    (binding [ana/*cljs-ns* ns-sym
              ana/*analyze-deps* false]
      (ana-api/no-warn
       (-> (ana/analyze (assoc (ana/empty-env) :ns (ns-info cenv ns-sym))
                        form nil {})
           strip-cljs-type)))))
