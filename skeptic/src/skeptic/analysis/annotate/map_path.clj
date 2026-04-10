(ns skeptic.analysis.annotate.map-path
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops.algebra :as amoa]))

(defn reduce-assoc-pairs
  [m-type kv-pairs]
  (reduce (fn [t [kn vn]]
            (let [lk (when (ac/literal-map-key? kn) (ac/literal-node-value kn))]
              (if (keyword? lk) (amoa/assoc-type t lk (:type vn)) t)))
          m-type kv-pairs))

(defn reduce-dissoc-keys
  [m-type key-nodes]
  (reduce (fn [t kn]
            (let [lk (when (ac/literal-map-key? kn) (ac/literal-node-value kn))]
              (if (keyword? lk) (amoa/dissoc-type t lk) t)))
          m-type key-nodes))
