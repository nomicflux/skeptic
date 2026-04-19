(ns skeptic.test-examples.conflict
  (:require [schema.core :as s]))

(defn ^{:schema (s/=> s/Int s/Int)
        :malli/schema [:=> [:cat :string] :string]}
  dual-annotated-fn
  [x]
  x)
