(ns p12-shadow-runtime.core
  (:require [schema.core :as s]
            [p12-shadow-runtime.shadow-only :as shadow]))

(s/defn bad :- s/Int
  []
  "wrong")

(def loaded-value shadow/value)
