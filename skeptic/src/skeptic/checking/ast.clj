(ns skeptic.checking.ast
  (:require [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.calls :as ac]
            [skeptic.checking.form :as cf]))

(def invoke-ops aapi/invoke-ops)

(defn distinctv
  [xs]
  (reduce (fn [acc x]
            (if (some #(= % x) acc)
              acc
              (conj acc x)))
          []
          xs))

(defn node-ref
  [node]
  (aapi/node-ref node))

(defn callee-ref
  [node]
  (aapi/callee-ref node))

(defn match-up-arglists
  [arg-nodes expected actual]
  (cf/spy :match-up-actual-list actual)
  (cf/spy :match-up-expected-list expected)
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(get arg-nodes n)
       (cf/spy :match-up-expected (get expected n expected-vararg))
       (cf/spy :match-up-actual (get actual n))])))

(defn local-resolution-path
  [local-node]
  (aapi/local-resolution-path local-node))

(defn local-vars-context
  [node]
  (aapi/local-vars-context node))

(defn call-refs
  [node]
  (aapi/call-refs node))

(defn call-node?
  [node]
  (aapi/call-node? node))

(defn- lookup-in-dict
  [dict ns-sym sym]
  (or (get dict sym)
      (get dict (ac/qualify-symbol ns-sym sym))))

(defn dict-entry
  [dict ns-sym sym]
  (lookup-in-dict dict ns-sym sym))

(defn unwrap-with-meta
  [node]
  (aapi/unwrap-with-meta node))
