(ns skeptic.analysis.annotate.match-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.match :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [tp]]))

(deftest case-conditional-narrow-for-lits-empty-picks-uses-anchor-test
  (testing "no branch matches → BottomType carrying anchor prov, no crash"
    (let [branches []
          result (sut/case-conditional-narrow-for-lits tp branches :k [:x])]
      (is (at/bottom-type? result))
      (is (= tp (prov/of result))))))

(deftest case-conditional-default-narrow-empty-default-uses-anchor-test
  (testing "no unmatched branches → BottomType carrying anchor prov, no crash"
    (let [branches []
          result (sut/case-conditional-default-narrow tp branches :k [:x])]
      (is (at/bottom-type? result))
      (is (= tp (prov/of result))))))
