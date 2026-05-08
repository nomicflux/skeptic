(ns skeptic.analysis.malli-spec.bridge-tuple-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest tuple-with-three-primitive-members
  (is (= (at/->VectorT tp [(at/->GroundT tp :int 'Int)
                           (at/->GroundT tp :str 'Str)
                           (at/->GroundT tp :keyword 'Keyword)]
                       nil)
         (sut/malli-spec->type tp [:tuple :int :string :keyword]))))

(deftest tuple-with-single-member
  (is (= (at/->VectorT tp [(at/->GroundT tp :int 'Int)] nil)
         (sut/malli-spec->type tp [:tuple :int]))))

(deftest tuple-bare-keyword-falls-back-to-dyn
  (is (= (at/Dyn tp)
         (sut/malli-spec->type tp [:tuple]))))

(deftest tuple-with-maybe-and-or-members
  (let [expected (at/->VectorT tp
                               [(at/->MaybeT tp (at/->GroundT tp :int 'Int))
                                (ato/union-type tp [(at/->GroundT tp :int 'Int)
                                                    (at/->GroundT tp :str 'Str)])]
                               nil)]
    (is (= expected
           (sut/malli-spec->type tp [:tuple [:maybe :int] [:or :int :string]])))))

(deftest tuple-with-and-member
  (let [expected (at/->VectorT tp
                               [(ato/intersection-type tp [(at/->GroundT tp :int 'Int)
                                                           (at/->GroundT tp :str 'Str)])
                                (at/->GroundT tp :keyword 'Keyword)]
                               nil)]
    (is (= expected
           (sut/malli-spec->type tp [:tuple [:and :int :string] :keyword])))))

(deftest tuple-with-unknown-leaf-falls-back-to-dyn-slot
  (let [result (sut/malli-spec->type tp [:tuple :int :uuid])]
    (is (= (at/->VectorT tp [(at/->GroundT tp :int 'Int) (at/Dyn tp)] nil)
           result))))
