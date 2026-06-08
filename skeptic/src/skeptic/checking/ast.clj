(ns skeptic.checking.ast
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.calls :as ac]))

(s/defn distinctv :- s/Any
  [xs]
  (reduce (fn [acc x]
            (if (some #(= % x) acc)
              acc
              (conj acc x)))
          []
          xs))

(s/defn match-up-arglists :- s/Any
  [arg-nodes :- [(s/maybe aas/AnnotatedNode)] expected actual]
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(get arg-nodes n)
       (get expected n expected-vararg)
       (get actual n)])))

(defn- lookup-in-dict
  [dict ns-sym sym]
  (or (get dict sym)
      (get dict (ac/qualify-symbol ns-sym sym))))

(s/defn dict-entry :- s/Any
  [dict ns-sym sym]
  (lookup-in-dict dict ns-sym sym))
