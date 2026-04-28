(ns skeptic.checking.ast
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.calls :as ac]
            [skeptic.checking.form :as cf]))

(def invoke-ops aapi/invoke-ops)

(s/defn distinctv :- s/Any
  [xs]
  (reduce (fn [acc x]
            (if (some #(= % x) acc)
              acc
              (conj acc x)))
          []
          xs))

(s/defn node-ref :- s/Any
  [node]
  (aapi/node-ref node))

(s/defn callee-ref :- s/Any
  [node]
  (aapi/callee-ref node))

(s/defn match-up-arglists :- s/Any
  [arg-nodes expected actual]
  (cf/spy :match-up-actual-list actual)
  (cf/spy :match-up-expected-list expected)
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(get arg-nodes n)
       (cf/spy :match-up-expected (get expected n expected-vararg))
       (cf/spy :match-up-actual (get actual n))])))

(s/defn local-resolution-path :- s/Any
  [local-node]
  (aapi/local-resolution-path local-node))

(s/defn local-vars-context :- s/Any
  [node]
  (aapi/local-vars-context node))

(s/defn call-refs :- s/Any
  [node]
  (aapi/call-refs node))

(s/defn call-node? :- s/Any
  [node]
  (aapi/call-node? node))

(defn- lookup-in-dict
  [dict ns-sym sym]
  (or (get dict sym)
      (get dict (ac/qualify-symbol ns-sym sym))))

(s/defn dict-entry :- s/Any
  [dict ns-sym sym]
  (lookup-in-dict dict ns-sym sym))

(s/defn unwrap-with-meta :- s/Any
  [node]
  (aapi/unwrap-with-meta node))
