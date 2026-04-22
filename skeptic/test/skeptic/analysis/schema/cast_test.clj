(ns skeptic.analysis.schema.cast-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.schema.cast :as sut]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest raw-schema-check-cast-adapts-to-type-domain-test
  (let [exact (sut/check-cast tp s/Int s/Int)
        target-dyn (sut/check-cast tp s/Int s/Any)
        map-cast (sut/check-cast tp {:a s/Any} {:a s/Int})]
    (is (:ok? exact))
    (is (= :exact (:rule exact)))
    (is (:ok? target-dyn))
    (is (= :target-dyn (:rule target-dyn)))
    (is (:ok? map-cast))
    (is (= :map (:rule map-cast)))))
