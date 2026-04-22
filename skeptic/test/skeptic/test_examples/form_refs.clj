(ns skeptic.test-examples.form-refs
  (:require [schema.core :as s]))

(s/defschema MapBody {:a s/Int :b s/Str})
(s/defschema VecBody [s/Int])
(s/defn fn-with-map-ann :- {:result s/Int :cache s/Str}
  [x :- s/Int]
  {:result x :cache "k"})
