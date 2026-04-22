(ns skeptic.analysis.map-ops.algebra-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis-test :as atst]
            [skeptic.provenance :as prov]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [schema.core :as s]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest assoc-dissoc-update-test
  (let [m (at/->MapT tp {(ato/exact-value-type tp :a) (atst/T s/Int)})]
    (testing "assoc overwrites key"
      (is (= (at/->MapT tp {(ato/exact-value-type tp :a) (atst/T s/Str)})
             (amoa/assoc-type m :a (atst/T s/Str)))))
    (testing "dissoc removes key"
      (is (= (at/->MapT tp {})
             (amoa/dissoc-type m :a))))
    (testing "update uses fn output"
      (is (= (at/->MapT tp {(ato/exact-value-type tp :a) (atst/T s/Str)})
             (amoa/update-type m :a (at/->FunT tp [(at/->FnMethodT tp [(atst/T s/Int)]
                                                                 (atst/T s/Str)
                                                                 1
                                                                 false
                                                                 '[x])])))))
    (testing "non-map is Dyn"
      (is (= (at/Dyn tp) (amoa/assoc-type (at/Dyn tp) :a (atst/T s/Int)))))))

(deftest merge-types-test
  (is (= (at/Dyn tp) (amoa/merge-types tp [(atst/T s/Int) (at/->MapT tp {})])))
  (is (at/map-type? (amoa/merge-types tp [(at/->MapT tp {(ato/exact-value-type tp :a) (atst/T s/Int)})
                                          (at/->MapT tp {(ato/exact-value-type tp :b) (atst/T s/Str)})]))))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil))

(deftest merge-types-container-owns-prov-test
  (testing "empty types with anchor does not crash and carries anchor prov"
    (let [result (amoa/merge-types tp [])]
      (is (= (at/Dyn tp) result))
      (is (= tp (prov/of result)))))
  (testing "result prov is the anchor, not derived from input map provs"
    (let [m1 (at/->MapT other-prov {(ato/exact-value-type other-prov :a) (atst/T s/Int)})
          m2 (at/->MapT other-prov {(ato/exact-value-type other-prov :b) (atst/T s/Str)})
          result (amoa/merge-types tp [m1 m2])]
      (is (at/map-type? result))
      (is (= tp (prov/of result))))))
