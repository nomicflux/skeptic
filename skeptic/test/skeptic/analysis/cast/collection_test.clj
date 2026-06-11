(ns skeptic.analysis.cast.collection-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as sut]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def tp (prov/make-provenance :inferred 'test-sym 'skeptic.test nil [] :clj))

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
        double-g (at/->GroundT tp {:class (oracle/host-handle Double)} 'Double)
        maybe-obj (at/->MaybeT tp (at/->GroundT tp {:class (oracle/host-handle Object)} 'Object))]
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

(deftest opaque-predicate-target-test
  ;; An unrecognized (s/pred ...) admits as an adapter leaf; it cannot disprove
  ;; a structured source, so the cast holds instead of reporting a mismatch
  ;; (regression: map literals flagged against (s/pred map?)-style params).
  (let [opaque (T (s/pred some? 'some?))
        map-src (T {:age s/Num :vegan? s/Bool})
        vec-src (T [s/Int])]
    (is (at/adapter-leaf-type? opaque))
    (is (= :opaque-predicate (:rule (sut/check-cast map-src opaque))))
    (is (:ok? (sut/check-cast map-src opaque)))
    (is (:ok? (sut/check-cast vec-src opaque)))))

(deftest pred-map?-witness-test
  ;; (s/pred map?) resolves through the predicate registry (demunged of the
  ;; direct-linking __NNNN counter) to an open-map witness: maps fit, a
  ;; non-map ground is a proveable mismatch.
  (let [target (T (s/pred map?))
        map-src (T {:age s/Num :vegan? s/Bool})]
    (is (at/map-type? target))
    (is (:ok? (sut/check-cast map-src target)))
    (is (not (:ok? (sut/check-cast (T s/Str) target))))))
