(ns skeptic.analysis.value-check-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.provenance :as prov]
            [skeptic.analysis.value-check :as avc]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest contains-key-type-classification-regression-test
  (is (= :unknown
         (avc/contains-key-type-classification (ab/schema->type tp {s/Keyword s/Any}) :a)))
  (is (= :always
         (avc/contains-key-type-classification (ab/schema->type tp {:a s/Int s/Keyword s/Any}) :a)))
  (is (= :unknown
         (avc/contains-key-type-classification (ab/schema->type tp {:a s/Int s/Keyword s/Any}) :b))))

(deftest semantic-value-satisfies-type-regression-test
  (is (avc/value-satisfies-type? [1 2 3] (ab/schema->type tp [s/Int])))
  (is (avc/value-satisfies-type? [1 2 3] (ab/schema->type tp [s/Int s/Int s/Int])))
  (is (not (avc/value-satisfies-type? [1 2 3] (ab/schema->type tp [s/Int s/Int])))))
