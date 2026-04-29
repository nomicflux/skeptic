(ns skeptic.checking.ast
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]
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
  [node :- (s/maybe aas/AnnotatedNode)]
  (aapi/node-ref node))

(s/defn callee-ref :- s/Any
  [node :- (s/maybe aas/AnnotatedNode)]
  (aapi/callee-ref node))

(s/defn match-up-arglists :- s/Any
  [arg-nodes :- [(s/maybe aas/AnnotatedNode)] expected actual]
  (cf/spy :match-up-actual-list actual)
  (cf/spy :match-up-expected-list expected)
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(get arg-nodes n)
       (cf/spy :match-up-expected (get expected n expected-vararg))
       (cf/spy :match-up-actual (get actual n))])))

(s/defn local-resolution-path :- s/Any
  [local-node :- aas/AnnotatedNode]
  (aapi/local-resolution-path local-node))

(s/defn local-vars-context :- s/Any
  [node :- aas/AnnotatedNode]
  (aapi/local-vars-context node))

(s/defn call-refs :- s/Any
  [node :- aas/AnnotatedNode]
  (aapi/call-refs node))

(s/defn call-node? :- s/Any
  [node :- aas/AnnotatedNode]
  (aapi/call-node? node))

(defn- lookup-in-dict
  [dict ns-sym sym]
  (or (get dict sym)
      (get dict (ac/qualify-symbol ns-sym sym))))

(s/defn dict-entry :- s/Any
  [dict ns-sym sym]
  (lookup-in-dict dict ns-sym sym))

(s/defn unwrap-with-meta :- s/Any
  [node :- aas/AnnotatedNode]
  (aapi/unwrap-with-meta node))
