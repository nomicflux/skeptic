(ns skeptic.test-examples.contracts-xns-schema
  (:require [schema.core :as s]))

(s/defschema XnsA
  {:k (s/eq :a)})

(s/defschema XnsB
  {:k (s/eq :b)})

(s/defn xns-dispatch
  [m]
  (get m :k))

(s/defschema XnsIn
  (s/conditional
    #(= :a (xns-dispatch %)) XnsA
    #(= :b (xns-dispatch %)) XnsB))

(s/defn f :- s/Int
  []
  1)
