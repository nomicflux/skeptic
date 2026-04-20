(ns skeptic.analysis.malli-spec.bridge-combined-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

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
        seen (atom [])]
    (walk/postwalk (fn [x] (swap! seen conj x) x) result)
    (is (not-any? #(= at/Dyn %) @seen))))
