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
    (is (at/type=? (atst/T s/Int) (sut/vector-slot-type vec-type 0)))
    (is (at/type=? (atst/T s/Int) (sut/coll-first-type vec-type)))
    (is (at/type=? (atst/T s/Int) (sut/coll-last-type seq-type)))
    (is (at/type=? (atst/T [s/Int]) (sut/coll-take-prefix-type vec-type 1)))))

(deftest collection-output-helpers-test
  (let [args [{:type (atst/T [s/Int])} {:type (atst/T [s/Int])}]]
    (is (at/seq-type? (sut/concat-output-type tp args)))
    (is (some? (sut/into-output-type args)))))

(deftest concat-output-type-container-owns-prov-test
  (testing "empty args with anchor does not crash and carries anchor prov"
    (let [result (sut/concat-output-type tp [])]
      (is (at/seq-type? result))
      (is (= tp (prov/of result)))))
  (testing "result prov source is the anchor, not derived from arg types"
    (let [other-vec (at/->VectorT other-prov [(at/->GroundT other-prov :int 'Int)] true)
          args [{:type other-vec} {:type other-vec}]
          result (sut/concat-output-type tp args)
          result-prov (prov/of result)]
      (is (at/seq-type? result))
      (is (= (:source tp) (:source result-prov)))
      (is (= (:qualified-sym tp) (:qualified-sym result-prov))))))

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

(defn- int-t
  [prov]
  (at/->GroundT prov :int 'Int))

(deftest coll-rest-output-type-vector-threads-refs-test
  (testing "non-homogeneous vector ≥2 items: refs has (count tail) entries"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] false)
          result (sut/coll-rest-output-type vt)
          refs (:refs (prov/of result))]
      (is (= 2 (count refs)))))
  (testing "1-item vector → seq fallback: refs has 1 entry"
    (let [vt (at/->VectorT tp [(int-t tp)] true)
          result (sut/coll-rest-output-type vt)
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))

(deftest coll-butlast-output-type-threads-refs-test
  (testing "3-item vector: refs count = 2"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] false)
          result (sut/coll-butlast-output-type vt)
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest coll-drop-last-output-type-threads-refs-test
  (testing "drop-last 1 from 3-item vector: refs count = 2"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] false)
          result (sut/coll-drop-last-output-type vt 1)
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest coll-take-prefix-type-threads-refs-test
  (testing "take 2 from 3-item vector: refs count = 2"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] false)
          result (sut/coll-take-prefix-type vt 2)
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest coll-drop-prefix-type-threads-refs-test
  (testing "drop all (empty result): refs is []"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp)] true)
          result (sut/coll-drop-prefix-type vt 2)
          refs (:refs (prov/of result))]
      (is (= [] refs))))
  (testing "drop 1 from 3-item vector: refs count = 2"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] false)
          result (sut/coll-drop-prefix-type vt 1)
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest coll-same-element-seq-type-threads-refs-test
  (testing "vector input: refs has 1 entry (the element)"
    (let [vt (at/->VectorT tp [(int-t tp)] true)
          result (sut/coll-same-element-seq-type vt)
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))

(deftest concat-output-type-empty-args-empty-refs-test
  (testing "empty args: refs is []"
    (let [result (sut/concat-output-type tp [])
          refs (:refs (prov/of result))]
      (is (= [] refs)))))

(deftest concat-output-type-non-empty-threads-joined-ref-test
  (testing "all args have seqish elements: refs has 1 entry (the joined)"
    (let [args [{:type (at/->VectorT tp [(int-t tp)] true)}
                {:type (at/->VectorT tp [(int-t tp)] true)}]
          result (sut/concat-output-type tp args)
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))

(deftest into-output-type-vector-target-threads-refs-test
  (testing "vector-target: refs count = 1"
    (let [args [{:type (at/->VectorT tp [(int-t tp)] true)}
                {:type (at/->VectorT tp [(int-t tp)] true)}]
          result (sut/into-output-type args)
          refs (:refs (prov/of result))]
      (is (at/vector-type? result))
      (is (= 1 (count refs))))))

(deftest into-output-type-seq-target-threads-refs-test
  (testing "seq-target: refs count = 1"
    (let [args [{:type (at/->SeqT tp [(int-t tp)] true)}
                {:type (at/->VectorT tp [(int-t tp)] true)}]
          result (sut/into-output-type args)
          refs (:refs (prov/of result))]
      (is (at/seq-type? result))
      (is (= 1 (count refs))))))

(deftest vector-to-homogeneous-seq-type-threads-refs-test
  (testing "non-homogeneous vector: refs has 1 entry"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp)] false)
          result (sut/vector-to-homogeneous-seq-type vt)
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))
