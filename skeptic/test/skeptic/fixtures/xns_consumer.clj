(ns skeptic.fixtures.xns-consumer
  (:require [schema.core :as s]
            [skeptic.fixtures.xns-provider :as p]))

(s/defn consumer-fn :- s/Str
  []
  (p/provider-fn))
