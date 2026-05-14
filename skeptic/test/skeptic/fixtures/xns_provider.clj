(ns skeptic.fixtures.xns-provider
  (:require [schema.core :as s]))

(s/defn provider-fn :- s/Int
  []
  1)
