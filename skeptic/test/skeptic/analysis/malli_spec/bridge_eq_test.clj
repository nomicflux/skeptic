(ns skeptic.analysis.malli-spec.bridge-eq-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.type-ops :as ato]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest eq-with-keyword-value
  (is (= (ato/exact-value-type tp :a)
         (sut/malli-spec->type tp [:= :a]))))

(deftest eq-with-integer-value
  (is (= (ato/exact-value-type tp 42)
         (sut/malli-spec->type tp [:= 42]))))

(deftest eq-with-string-value
  (is (= (ato/exact-value-type tp "hello")
         (sut/malli-spec->type tp [:= "hello"]))))
