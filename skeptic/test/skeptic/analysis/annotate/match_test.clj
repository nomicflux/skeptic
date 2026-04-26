(ns skeptic.analysis.annotate.match-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.match :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
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

(deftest narrow-conditional-by-discriminator-drop-test
  (testing "with drop-discriminator? true, removes discriminator key from branches"
    (let [map-type (at/->MapT tp {(ato/exact-value-type tp :k) (at/->GroundT tp :int 'Int)
                                   (ato/exact-value-type tp :x) (at/->GroundT tp :str 'String)})
          pred (constantly true)
          branches [[pred map-type]]
          result (sut/narrow-conditional-by-discriminator tp branches :k [:a] {:drop-discriminator? true})]
      (is (not (at/bottom-type? result)))
      (is (not (contains? (:entries result) (ato/exact-value-type tp :k)))))))

(deftest narrow-conditional-by-discriminator-keep-test
  (testing "with drop-discriminator? false, retains discriminator key in branches"
    (let [map-type (at/->MapT tp {(ato/exact-value-type tp :k) (at/->GroundT tp :int 'Int)
                                   (ato/exact-value-type tp :x) (at/->GroundT tp :str 'String)})
          pred (constantly true)
          branches [[pred map-type]]
          result (sut/narrow-conditional-by-discriminator tp branches :k [:a] {:drop-discriminator? false})]
      (is (not (at/bottom-type? result)))
      (is (contains? (:entries result) (ato/exact-value-type tp :k))))))

(deftest narrow-conditional-default-keep-test
  (testing "default sibling with drop-discriminator? false retains discriminator key"
    (let [map-type (at/->MapT tp {(ato/exact-value-type tp :k) (at/->GroundT tp :int 'Int)
                                   (ato/exact-value-type tp :x) (at/->GroundT tp :str 'String)})
          pred (constantly false)
          branches [[pred map-type]]
          result (sut/narrow-conditional-default tp branches :k [:a] {:drop-discriminator? false})]
      (is (not (at/bottom-type? result)))
      (is (contains? (:entries result) (ato/exact-value-type tp :k))))))
