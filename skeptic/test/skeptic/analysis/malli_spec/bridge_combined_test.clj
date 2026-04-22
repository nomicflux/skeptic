(ns skeptic.analysis.malli-spec.bridge-combined-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest converter-round-trip-combined
  (let [schema [:=> [:cat [:maybe :int]] [:or :int :string]]
        result (sut/malli-spec->type tp schema)
        expected (at/->FunT tp [(at/->FnMethodT tp [(at/->MaybeT tp (at/->GroundT tp :int 'Int))]
                    (ato/union-type tp [(at/->GroundT tp :int 'Int)
                                     (at/->GroundT tp :str 'Str)])
                    1
                    false
                    '[arg0])])]
    (is (= expected result))))

(deftest no-dyn-in-combined-type
  (let [schema [:=> [:cat [:maybe :int]] [:or :int :string]]
        result (sut/malli-spec->type tp schema)
        seen (atom [])]
    (walk/postwalk (fn [x] (swap! seen conj x) x) result)
    (is (not-any? #(= (at/Dyn tp) %) @seen))))
