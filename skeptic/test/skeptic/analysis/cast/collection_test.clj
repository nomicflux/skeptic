(ns skeptic.analysis.cast.collection-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as sut]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'test-sym 'skeptic.test nil))

(defn T
  [schema]
  (ab/schema->type tp schema))

(deftest vector-and-cross-collection-rules-test
  (let [tuple-any (T [(s/one s/Any 'a) (s/one s/Any 'b) (s/one s/Any 'c)])
        homogeneous-int (T [s/Int])
        triple-int (T [(s/one s/Int 'a) (s/one s/Int 'b) (s/one s/Int 'c)])
        pair-int (T [(s/one s/Int 'a) (s/one s/Int 'b)])
        quad-int (T [(s/one s/Int 'a) (s/one s/Int 'b) (s/one s/Int 'c) (s/one s/Int 'd)])
        seq-int (T (list s/Int))
        seq-str (T (list s/Str))]
    (is (= :vector (:rule (sut/check-cast tuple-any homogeneous-int))))
    (is (:ok? (sut/check-cast tuple-any triple-int)))
    (is (= :vector-arity-mismatch
           (:reason (sut/check-cast tuple-any pair-int))))
    (is (= :vector-arity-mismatch
           (:reason (sut/check-cast tuple-any quad-int))))
    (is (= :seq-to-vector (:rule (sut/check-cast seq-int homogeneous-int))))
    (is (= :seq-to-vector (:rule (sut/check-cast seq-str homogeneous-int))))
    (is (= :vector-to-seq (:rule (sut/check-cast homogeneous-int seq-int))))))

(deftest set-and-leaf-rules-test
  (let [source-set (at/->SetT tp #{(T s/Int) (T s/Str)} false)
        target-set (at/->SetT tp #{(T s/Any) (T s/Str)} false)
        bad-set (at/->SetT tp #{(T s/Int)} false)
        int-g (at/->GroundT tp :int 'Int)
        numeric-dyn (at/NumericDyn tp)
        double-g (at/->GroundT tp {:class java.lang.Double} 'Double)
        maybe-obj (at/->MaybeT tp (at/->GroundT tp {:class java.lang.Object} 'Object))]
    (is (= :set (:rule (sut/check-cast source-set target-set))))
    (is (= :set-cardinality-mismatch
           (:reason (sut/check-cast source-set bad-set))))
    (is (:ok? (sut/check-cast int-g numeric-dyn)))
    (is (:ok? (sut/check-cast numeric-dyn int-g)))
    (is (:ok? (sut/check-cast double-g numeric-dyn)))
    (is (:ok? (sut/check-cast numeric-dyn double-g)))
    (is (not (:ok? (sut/check-cast double-g int-g))))
    (is (not (:ok? (sut/check-cast int-g double-g))))
    (is (:ok? (sut/check-cast int-g maybe-obj)))
    (is (not (:ok? (sut/check-cast maybe-obj int-g))))
    (is (avc/value-satisfies-type? [1 2 3] (T [s/Int])))
    (is (not (avc/value-satisfies-type? [1 2 3] (T [(s/one s/Int 'a) (s/one s/Int 'b)]))))))
