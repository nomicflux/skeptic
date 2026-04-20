(ns skeptic.checking.pipeline.malli-contracts-integration-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline.support :as ps]))

(deftest combined-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/combined-success)))))

(deftest combined-bad-fails
  (is (ps/single-failure?
       'skeptic.test-examples.malli-contracts/combined-bad
       :bad-keyword)))

(deftest converter-round-trip-combined
  (let [schema [:=> [:cat [:maybe :int]] [:or :int :string]]
        result (sut/malli-spec->type schema)
        expected (at/->FunT
                  [(at/->FnMethodT
                    [(at/->MaybeT (at/->GroundT :int 'Int))]
                    (ato/union-type [(at/->GroundT :int 'Int)
                                    (at/->GroundT :str 'Str)])
                    1
                    false)])]
    (is (= expected result))))

(deftest no-dyn-in-combined-type
  (let [schema [:=> [:cat [:maybe :int]] [:or :int :string]]
        result (sut/malli-spec->type schema)
        has-dyn? (some (fn [x]
                         (= at/Dyn x))
                       (walk/postwalk (fn [x] x) result))]
    (is (not has-dyn?))))
