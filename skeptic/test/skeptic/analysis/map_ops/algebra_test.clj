(ns skeptic.analysis.map-ops.algebra-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.provenance :as prov]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.test-helpers :refer [is-type= T tp]]
            [schema.core :as s]))

(deftest assoc-dissoc-update-test
  (let [m (at/->MapT tp {(ato/exact-value-type tp :a) (T s/Int)})]
    (testing "assoc overwrites key"
      (is-type= (at/->MapT tp {(ato/exact-value-type tp :a) (T s/Str)})
                (amoa/assoc-type m :a (T s/Str))))
    (testing "dissoc removes key"
      (is-type= (at/->MapT tp {})
                (amoa/dissoc-type m :a)))
    (testing "update uses fn output"
      (is-type= (at/->MapT tp {(ato/exact-value-type tp :a) (T s/Str)})
                (amoa/update-type m :a (at/->FunT tp [(at/->FnMethodT tp [(T s/Int)]
                                                                      (T s/Str)
                                                                      1
                                                                      false
                                                                      '[x])]))))
    (testing "non-map is Dyn"
      (is-type= (at/Dyn tp) (amoa/assoc-type (at/Dyn tp) :a (T s/Int))))))

(deftest merge-types-test
  (is-type= (at/Dyn tp) (amoa/merge-types tp [(T s/Int) (at/->MapT tp {})]))
  (is (at/map-type? (amoa/merge-types tp [(at/->MapT tp {(ato/exact-value-type tp :a) (T s/Int)})
                                          (at/->MapT tp {(ato/exact-value-type tp :b) (T s/Str)})]))))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil))

(deftest merge-types-container-owns-prov-test
  (testing "empty types with anchor does not crash and carries anchor prov"
    (let [result (amoa/merge-types tp [])]
      (is-type= (at/Dyn tp) result)
      (is (= tp (prov/of result)))))
  (testing "result prov is the anchor, not derived from input map provs"
    (let [m1 (at/->MapT other-prov {(ato/exact-value-type other-prov :a) (T s/Int)})
          m2 (at/->MapT other-prov {(ato/exact-value-type other-prov :b) (T s/Str)})
          result (amoa/merge-types tp [m1 m2])
          result-prov (prov/of result)]
      (is (at/map-type? result))
      (is (= (:source tp) (:source result-prov)))
      (is (= (:qualified-sym tp) (:qualified-sym result-prov)))
      (is (= (:declared-in tp) (:declared-in result-prov))))))

(deftest assoc-type-threads-refs-test
  (testing "assoc onto map type: refs has 3 entries (base, key, value)"
    (let [m (at/->MapT tp {})
          result (amoa/assoc-type m :a (at/->GroundT tp :int 'Int))
          refs (:refs (prov/of result))]
      (is (= 3 (count refs))))))

(deftest dissoc-type-threads-refs-test
  (testing "dissoc from map type: refs has 2 entries (base, removed-key)"
    (let [m (at/->MapT tp {(at/->ValueT tp (at/->GroundT tp :keyword 'Keyword) :a)
                           (at/->GroundT tp :int 'Int)})
          result (amoa/dissoc-type m :a)
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))
