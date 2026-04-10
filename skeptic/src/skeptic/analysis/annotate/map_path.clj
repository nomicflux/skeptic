(ns skeptic.analysis.annotate.map-path
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops.algebra :as amoa]))

(defn reduce-assoc-pairs
  [m-type kv-pairs]
  (reduce (fn [type [key-node value-node]]
            (if-let [literal (and (ac/literal-map-key? key-node)
                                  (ac/literal-node-value key-node))]
              (if (keyword? literal)
                (amoa/assoc-type type literal (:type value-node))
                type)
              type))
          m-type
          kv-pairs))

(defn reduce-dissoc-keys
  [m-type key-nodes]
  (reduce (fn [type key-node]
            (if-let [literal (and (ac/literal-map-key? key-node)
                                  (ac/literal-node-value key-node))]
              (if (keyword? literal)
                (amoa/dissoc-type type literal)
                type)
              type))
          m-type
          key-nodes))
