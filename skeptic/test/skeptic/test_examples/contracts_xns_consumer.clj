(ns skeptic.test-examples.contracts-xns-consumer
  (:require [schema.core :as s]
            [skeptic.test-examples.contracts-xns-schema :as schema]))

(s/defn consume-single
  [{:keys [k]} :- schema/Single]
  k)
