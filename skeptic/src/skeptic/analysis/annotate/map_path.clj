(ns skeptic.analysis.annotate.map-path
  (:require [schema.core :as s]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.types.schema :as ats]))

(s/defn reduce-assoc-pairs :- ats/SemanticType
  [m-type :- ats/SemanticType
   kv-pairs :- s/Any]
  (reduce (fn [type [key-node value-node]]
            (if-let [literal (and (ac/literal-map-key? key-node)
                                  (ac/literal-node-value key-node))]
              (if (keyword? literal)
                (amoa/assoc-type type literal (:type value-node))
                type)
              type))
          m-type
          kv-pairs))

(s/defn reduce-dissoc-keys :- ats/SemanticType
  [m-type :- ats/SemanticType
   key-nodes :- s/Any]
  (reduce (fn [type key-node]
            (if-let [literal (and (ac/literal-map-key? key-node)
                                  (ac/literal-node-value key-node))]
              (if (keyword? literal)
                (amoa/dissoc-type type literal)
                type)
              type))
          m-type
          key-nodes))
