(ns skeptic.analysis.annotate.coll-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.coll :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [is-type= T tp some!]]))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil))

(deftest collection-shape-helpers-test
  (let [vec-type (T [(s/one s/Int 'a) (s/one s/Int 'b)])
        homog-seq-type (T [s/Int])]
    (is-type= (T s/Int) (sut/vector-slot-type vec-type 0))
    (is-type= (T s/Int) (sut/coll-first-type vec-type))
    (is-type= (T s/Int) (sut/coll-last-type homog-seq-type))
    (is-type= (T [(s/one s/Int 'a)]) (sut/coll-take-prefix-type vec-type 1))))

(deftest collection-output-helpers-test
  (let [args [{:type (T [s/Int])} {:type (T [s/Int])}]]
    (is (at/seq-type? (sut/concat-output-type tp args)))
    (is (some? (sut/into-output-type args)))))

(deftest concat-output-type-container-owns-prov-test
  (testing "empty args with anchor does not crash and carries anchor prov"
    (let [result (some! (sut/concat-output-type tp []))]
      (is (at/seq-type? result))
      (is (= tp (prov/of result)))))
  (testing "result prov source is the anchor, not derived from arg types"
    (let [other-vec (at/->VectorT other-prov [] (at/->GroundT other-prov :int 'Int))
          args [{:type other-vec} {:type other-vec}]
          result (some! (sut/concat-output-type tp args))
          result-prov (prov/of result)]
      (is (at/seq-type? result))
      (is (= (:source tp) (:source result-prov)))
      (is (= (:qualified-sym tp) (:qualified-sym result-prov))))))

(deftest seqish-element-type-empty-closed-collections-return-nil-test
  (testing "empty closed vector has no elements; seqish-element-type is nil"
    (let [empty-vec (at/->VectorT tp [] nil)]
      (is (nil? (sut/seqish-element-type empty-vec)))))
  (testing "empty closed seq has no elements; seqish-element-type is nil"
    (let [empty-seq (at/->SeqT tp [] nil)]
      (is (nil? (sut/seqish-element-type empty-seq))))))

(defn- int-t
  [prov]
  (at/->GroundT prov :int 'Int))

(deftest coll-rest-output-type-vector-threads-refs-test
  (testing "closed vector ≥2 items: rest is homogeneous; refs has 1 entry"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] nil)
          result (some! (sut/coll-rest-output-type vt))
          refs (:refs (prov/of result))]
      (is (at/seq-type? result))
      (is (= 1 (count refs)))))
  (testing "homogeneous vector → seq fallback: refs has 1 entry"
    (let [vt (at/->VectorT tp [] (int-t tp))
          result (some! (sut/coll-rest-output-type vt))
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))

(deftest coll-butlast-output-type-threads-refs-test
  (testing "3-item vector: refs count = 2"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] nil)
          result (some! (sut/coll-butlast-output-type vt))
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest coll-drop-last-output-type-threads-refs-test
  (testing "drop-last 1 from 3-item vector: refs count = 2"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] nil)
          result (some! (sut/coll-drop-last-output-type vt 1))
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest coll-take-prefix-type-threads-refs-test
  (testing "take 2 from 3-item vector: refs count = 2"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] nil)
          result (some! (sut/coll-take-prefix-type vt 2))
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest coll-drop-prefix-type-threads-refs-test
  (testing "drop 2 from homogeneous vector: tail still present, refs count = 1"
    (let [vt (at/->VectorT tp [] (int-t tp))
          result (some! (sut/coll-drop-prefix-type vt 2))
          refs (:refs (prov/of result))]
      (is (= 1 (count refs)))))
  (testing "drop 1 from 3-item closed vector: refs count = 2"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp) (int-t tp)] nil)
          result (some! (sut/coll-drop-prefix-type vt 1))
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest coll-same-element-seq-type-threads-refs-test
  (testing "homogeneous vector input: refs has 1 entry (the element)"
    (let [vt (at/->VectorT tp [] (int-t tp))
          result (some! (sut/coll-same-element-seq-type vt))
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))

(deftest concat-output-type-empty-args-empty-refs-test
  (testing "empty args: refs is []"
    (let [result (some! (sut/concat-output-type tp []))
          refs (:refs (prov/of result))]
      (is (= [] refs)))))

(deftest concat-output-type-non-empty-threads-joined-ref-test
  (testing "all args have seqish elements: refs has 1 entry (the joined)"
    (let [args [{:type (at/->VectorT tp [] (int-t tp))}
                {:type (at/->VectorT tp [] (int-t tp))}]
          result (some! (sut/concat-output-type tp args))
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))

(deftest into-output-type-vector-target-threads-refs-test
  (testing "vector-target: refs count = 1"
    (let [args [{:type (at/->VectorT tp [] (int-t tp))}
                {:type (at/->VectorT tp [] (int-t tp))}]
          result (some! (sut/into-output-type args))
          refs (:refs (prov/of result))]
      (is (at/vector-type? result))
      (is (= 1 (count refs))))))

(deftest into-output-type-seq-target-threads-refs-test
  (testing "seq-target: refs count = 1"
    (let [args [{:type (at/->SeqT tp [] (int-t tp))}
                {:type (at/->VectorT tp [] (int-t tp))}]
          result (some! (sut/into-output-type args))
          refs (:refs (prov/of result))]
      (is (at/seq-type? result))
      (is (= 1 (count refs))))))

(deftest vector-to-homogeneous-seq-type-threads-refs-test
  (testing "closed multi-item vector: refs has 1 entry"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp)] nil)
          result (some! (sut/vector-to-homogeneous-seq-type vt))
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))
