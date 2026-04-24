(ns skeptic.analysis.malli-spec.bridge-enum-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.type-ops :as ato]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest enum-with-two-keyword-members
  (is (= (ato/union-type tp [(ato/exact-value-type tp :a) (ato/exact-value-type tp :b)])
         (sut/malli-spec->type tp [:enum :a :b]))))

(deftest enum-with-single-member-short-circuits
  (is (= (ato/union-type tp [(ato/exact-value-type tp :a)])
         (sut/malli-spec->type tp [:enum :a]))))

(deftest enum-with-properties-ignores-properties
  (is (= (sut/malli-spec->type tp [:enum :x :y])
         (sut/malli-spec->type tp [:enum {:title "c"} :x :y]))))

(deftest enum-with-heterogeneous-members
  (is (= (ato/union-type tp [(ato/exact-value-type tp :a)
                             (ato/exact-value-type tp "b")
                             (ato/exact-value-type tp 1)])
         (sut/malli-spec->type tp [:enum :a "b" 1]))))
