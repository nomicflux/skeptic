(ns skeptic.analysis.annotate.prune
  "Recursive AST-node prune. Drops slots that no skeptic reader consumes
   and that would otherwise root very large per-node payloads through the
   locals env and analyzer-state references on deep cljs ASTs.

   Removed: `:env` everywhere; `:info` everywhere except cljs `:var` ops,
   where it is reduced to `{:name ... :meta ...}` (the call-symbol path at
   `annotate/api.clj:42` plus the Var-metadata path at
   `schema/collect/cljs.clj:94` / `malli_spec/collect/cljs.clj:57`).
   Recurses through the analyzer's `:children`
   slot the same way as the cljs intake walker; AST shape is otherwise
   preserved so every existing reader of `:init` / `:binding-init` /
   `:fn-binding-node` / `:meta` continues to see the fields it expects."
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.schema :as aas]))

(defn- node?
  [v]
  (and (map? v) (contains? v :op)))

(defn- prune-fields
  [n]
  (let [n (dissoc n :env)
        n (if (and (= :fn (:op n)) (map? (:name n)))
            (assoc n :name (or (:form (:name n)) (:name (:name n))))
            n)]
    (cond
      (not (contains? n :info)) n
      (= :var (:op n))          (update n :info select-keys [:name :meta])
      :else                     (dissoc n :info))))

(s/defn project-node :- aas/AnnotatedNode
  [node :- aas/AnnotatedNode]
  (let [n (prune-fields node)]
    (reduce (fn [a k]
              (let [v (get a k)]
                (cond
                  (node? v)
                  (assoc a k (project-node v))

                  (and (vector? v) (seq v) (every? node? v))
                  (assoc a k (mapv project-node v))

                  :else a)))
            n
            (:children n))))
