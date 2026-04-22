(ns skeptic.analysis.annotate.coll-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.coll :as sut]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [tp]]))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil))

(deftest collection-shape-helpers-test
  (let [vec-type (atst/T [s/Int s/Int])
        seq-type (atst/T [s/Int])]
    (is (sut/vec-homogeneous-items? [(atst/T s/Int) (atst/T s/Int)]))
    (is (= (atst/T s/Int) (sut/vector-slot-type vec-type 0)))
    (is (= (atst/T s/Int) (sut/coll-first-type vec-type)))
    (is (= (atst/T s/Int) (sut/coll-last-type seq-type)))
    (is (= (atst/T [s/Int]) (sut/coll-take-prefix-type vec-type 1)))))

(deftest collection-output-helpers-test
  (let [args [{:type (atst/T [s/Int])} {:type (atst/T [s/Int])}]]
    (is (at/seq-type? (sut/concat-output-type tp args)))
    (is (some? (sut/into-output-type args)))))

(deftest concat-output-type-container-owns-prov-test
  (testing "empty args with anchor does not crash and carries anchor prov"
    (let [result (sut/concat-output-type tp [])]
      (is (at/seq-type? result))
      (is (= tp (prov/of result)))))
  (testing "result prov is the anchor, not derived from arg types"
    (let [other-vec (at/->VectorT other-prov [(at/->GroundT other-prov :int 'Int)] true)
          args [{:type other-vec} {:type other-vec}]
          result (sut/concat-output-type tp args)]
      (is (at/seq-type? result))
      (is (= tp (prov/of result))))))

(deftest seqish-element-type-empty-items-uses-container-prov-test
  (testing "non-homogeneous empty vector does not crash; element-type carries container prov"
    (let [empty-vec (at/->VectorT tp [] false)
          result (sut/seqish-element-type empty-vec)]
      (is (some? result))
      (is (= tp (prov/of result)))))
  (testing "non-homogeneous empty seq does not crash; element-type carries container prov"
    (let [empty-seq (at/->SeqT tp [] false)
          result (sut/seqish-element-type empty-seq)]
      (is (some? result))
      (is (= tp (prov/of result))))))
