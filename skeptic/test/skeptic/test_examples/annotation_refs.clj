(ns skeptic.test-examples.annotation-refs
  (:require [schema.core :as s]))

(s/defschema RefSchema s/Int)

(s/defn annotated-fn :- RefSchema
  [x :- s/Int]
  x)

(s/def annotated-val :- RefSchema 42)
