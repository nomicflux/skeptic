(ns skeptic.test-examples.nullability-xns-schema
  (:require [schema.core :as s]))

(s/defn f :- (s/maybe s/Int)
  []
  nil)
