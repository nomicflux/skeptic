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

(deftest narrow-conditional-by-discriminator-keep-test
  (testing "retains discriminator key in branches"
    (let [map-type (at/->MapT tp {(ato/exact-value-type tp :k) (at/->GroundT tp :int 'Int)
                                   (ato/exact-value-type tp :x) (at/->GroundT tp :str 'String)})
          pred (constantly true)
          branches [[pred map-type]]
          result (sut/narrow-conditional-by-discriminator tp branches [:k] [:a])]
      (is (not (at/bottom-type? result)))
      (is (contains? (:entries result) (ato/exact-value-type tp :k))))))

(deftest narrow-conditional-default-keep-test
  (testing "default sibling retains discriminator key"
    (let [map-type (at/->MapT tp {(ato/exact-value-type tp :k) (at/->GroundT tp :int 'Int)
                                   (ato/exact-value-type tp :x) (at/->GroundT tp :str 'String)})
          pred (constantly false)
          branches [[pred map-type]]
          result (sut/narrow-conditional-default tp branches [:k] [:a])]
      (is (not (at/bottom-type? result)))
      (is (contains? (:entries result) (ato/exact-value-type tp :k))))))

(deftest narrow-conditional-by-discriminator-multi-step-path
  (testing "2-element path: pred probes (get-in m [:x :k]), selects branch b only"
    (let [map-type-a (at/->MapT tp {(ato/exact-value-type tp :x) (at/->GroundT tp :str 'String)
                                     (ato/exact-value-type tp :disc) (at/->GroundT tp :str 'String)})
          map-type-b (at/->MapT tp {(ato/exact-value-type tp :x) (at/->GroundT tp :int 'Int)
                                     (ato/exact-value-type tp :disc) (at/->GroundT tp :str 'String)})
          pred-a (fn [m] (= (get-in m [:x :k]) "a"))
          pred-b (fn [m] (= (get-in m [:x :k]) "b"))
          branches [[pred-a map-type-a] [pred-b map-type-b]]
          path [:x :k]
          result (sut/narrow-conditional-by-discriminator tp branches path ["b"])]
      (is (not (at/bottom-type? result)))
      (is (at/map-type? result))
      (is (contains? (:entries result) (ato/exact-value-type tp :disc))))))

(deftest descriptor-path-bypasses-runtime-probe
  (testing "descriptor with matching values selects branch even when pred returns false"
    (let [a-type (at/->GroundT tp :str 'String)
          b-type (at/->GroundT tp :int 'Int)
          always-false (constantly false)
          path [:k]
          branches [[always-false a-type {:path [{:value :k}] :values ["a"]}]
                    [always-false b-type {:path [{:value :k}] :values ["b"]}]]
          result (sut/narrow-conditional-by-discriminator
                   tp branches path ["b"])]
      (is (= b-type result)))))
