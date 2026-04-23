(ns skeptic.analysis.annotate.shared-call-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.shared-call :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [tp]]))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil))

(deftest shared-call-contains-uses-anchor-prov-test
  (testing ":contains returns bool with default-output-type's prov (not args' provs)"
    (let [default-output-type (at/Dyn tp)
          args [{:type (at/->MapT other-prov {})}
                {:type (at/->GroundT other-prov :keyword 'Keyword)}]
          result (sut/shared-call-output-type nil :contains args default-output-type)]
      (is (at/ground-type? result))
      (is (= tp (prov/of result))))))

(deftest shared-call-merge-uses-anchor-prov-test
  (testing ":merge returns map type with default-output-type's prov (not arg map provs)"
    (let [default-output-type (at/Dyn tp)
          args [{:type (at/->MapT other-prov {(ato/exact-value-type other-prov :a)
                                              (at/->GroundT other-prov :int 'Int)})}
                {:type (at/->MapT other-prov {(ato/exact-value-type other-prov :b)
                                              (at/->GroundT other-prov :int 'Int)})}]
          result (sut/shared-call-output-type nil :merge args default-output-type)
          result-prov (prov/of result)]
      (is (at/map-type? result))
      (is (= (:source tp) (:source result-prov)))
      (is (= (:qualified-sym tp) (:qualified-sym result-prov)))
      (is (= (:declared-in tp) (:declared-in result-prov)))))
  (testing ":merge with empty args does not crash; result carries anchor prov"
    (let [default-output-type (at/Dyn tp)
          result (sut/shared-call-output-type nil :merge [] default-output-type)]
      (is (= tp (prov/of result))))))
