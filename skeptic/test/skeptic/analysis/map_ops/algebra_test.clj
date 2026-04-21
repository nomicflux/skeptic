(ns skeptic.analysis.map-ops.algebra-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [schema.core :as s]))

(deftest assoc-dissoc-update-test
  (let [m (at/->MapT {(ato/exact-value-type :a) (atst/T s/Int)})]
    (testing "assoc overwrites key"
      (is (= (at/->MapT {(ato/exact-value-type :a) (atst/T s/Str)})
             (amoa/assoc-type m :a (atst/T s/Str)))))
    (testing "dissoc removes key"
      (is (= (at/->MapT {})
             (amoa/dissoc-type m :a))))
    (testing "update uses fn output"
      (is (= (at/->MapT {(ato/exact-value-type :a) (atst/T s/Str)})
             (amoa/update-type m :a (at/->FunT [(at/->FnMethodT [(atst/T s/Int)]
                                                                 (atst/T s/Str)
                                                                 1
                                                                 false
                                                                 '[x])])))))
    (testing "non-map is Dyn"
      (is (= at/Dyn (amoa/assoc-type at/Dyn :a (atst/T s/Int)))))))

(deftest merge-types-test
  (is (= at/Dyn (amoa/merge-types [(atst/T s/Int) (at/->MapT {})])))
  (is (at/map-type? (amoa/merge-types [(at/->MapT {(ato/exact-value-type :a) (atst/T s/Int)})
                                       (at/->MapT {(ato/exact-value-type :b) (atst/T s/Str)})]))))
