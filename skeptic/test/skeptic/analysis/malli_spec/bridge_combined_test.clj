(ns skeptic.analysis.malli-spec.bridge-combined-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

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

(deftest converter-round-trip-extended-leaves
  (let [schema [:=> [:cat :double :qualified-keyword [:maybe :symbol]] [:or :double :nil]]
        result (sut/malli-spec->type tp schema)
        expected (at/->FunT tp [(at/->FnMethodT tp [(at/->GroundT tp :double 'Double)
                                                    (at/->GroundT tp :keyword 'Keyword)
                                                    (at/->MaybeT tp (at/->GroundT tp :symbol 'Symbol))]
                                                 (ato/union-type tp [(at/->GroundT tp :double 'Double)
                                                                     (ato/exact-value-type tp nil)])
                                                 3
                                                 false
                                                 '[arg0 arg1 arg2])])]
    (is (= expected result))))

(deftest converter-round-trip-float-mixed
  (let [schema [:=> [:cat :float [:maybe :int]] [:or :float :double]]
        result (sut/malli-spec->type tp schema)
        expected (at/->FunT tp [(at/->FnMethodT tp [(at/->GroundT tp :float 'Float)
                                                    (at/->MaybeT tp (at/->GroundT tp :int 'Int))]
                                                 (ato/union-type tp [(at/->GroundT tp :float 'Float)
                                                                     (at/->GroundT tp :double 'Double)])
                                                 2
                                                 false
                                                 '[arg0 arg1])])]
    (is (= expected result))))

(deftest converter-round-trip-and-mixed
  (let [schema [:=> [:cat [:and :int :int] [:or :int :string]] [:and [:maybe :int] :string]]
        result (sut/malli-spec->type tp schema)
        expected (at/->FunT tp [(at/->FnMethodT tp [(at/->GroundT tp :int 'Int)
                                                    (ato/union-type tp [(at/->GroundT tp :int 'Int)
                                                                        (at/->GroundT tp :str 'Str)])]
                                                 (ato/intersection-type tp [(at/->MaybeT tp (at/->GroundT tp :int 'Int))
                                                                            (at/->GroundT tp :str 'Str)])
                                                 2
                                                 false
                                                 '[arg0 arg1])])]
    (is (= expected result))))
