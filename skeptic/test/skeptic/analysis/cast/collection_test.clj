(ns skeptic.analysis.cast.collection-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as sut]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.types :as at]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest vector-and-cross-collection-rules-test
  (let [tuple-any (T [s/Any s/Any s/Any])
        homogeneous-int (T [s/Int])
        triple-int (T [s/Int s/Int s/Int])
        pair-int (T [s/Int s/Int])
        quad-int (T [s/Int s/Int s/Int s/Int])
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
  (let [source-set (at/->SetT #{(T s/Int) (T s/Str)} false)
        target-set (at/->SetT #{(T s/Any) (T s/Str)} false)
        bad-set (at/->SetT #{(T s/Int)} false)
        int-g (at/->GroundT :int 'Int)
        num-g (at/->GroundT {:class java.lang.Number} 'Number)
        maybe-obj (at/->MaybeT (at/->GroundT {:class java.lang.Object} 'Object))]
    (is (= :set (:rule (sut/check-cast source-set target-set))))
    (is (= :set-cardinality-mismatch
           (:reason (sut/check-cast source-set bad-set))))
    (is (:ok? (sut/check-cast int-g num-g)))
    (is (not (:ok? (sut/check-cast num-g int-g))))
    (is (:ok? (sut/check-cast int-g maybe-obj)))
    (is (not (:ok? (sut/check-cast maybe-obj int-g))))
    (is (avc/value-satisfies-type? [1 2 3] (T [s/Int])))
    (is (not (avc/value-satisfies-type? [1 2 3] (T [s/Int s/Int]))))))
