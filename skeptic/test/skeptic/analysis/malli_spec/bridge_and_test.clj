(ns skeptic.analysis.malli-spec.bridge-and-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(deftest and-with-two-primitive-members
  (is (= (ato/intersection-type tp [(at/->GroundT tp :int 'Int) (at/->GroundT tp :str 'Str)])
         (sut/malli-spec->type tp [:and :int :string]))))

(deftest and-with-single-member-short-circuits
  (is (= (ato/intersection-type tp [(at/->GroundT tp :int 'Int)])
         (sut/malli-spec->type tp [:and :int]))))

(deftest and-with-duplicate-members-collapses
  (is (= (at/->GroundT tp :int 'Int)
         (sut/malli-spec->type tp [:and :int :int]))))

(deftest and-with-maybe-member-documents-observed-shape
  (let [expected (ato/intersection-type tp [(at/->MaybeT tp (at/->GroundT tp :int 'Int))
                                            (at/->GroundT tp :str 'Str)])]
    (is (= expected (sut/malli-spec->type tp [:and [:maybe :int] :string])))))

(deftest and-with-or-member-documents-observed-shape
  (let [expected (ato/intersection-type tp [(ato/union-type tp [(at/->GroundT tp :int 'Int)
                                                                (at/->GroundT tp :str 'Str)])
                                            (at/->GroundT tp :int 'Int)])]
    (is (= expected (sut/malli-spec->type tp [:and [:or :int :string] :int])))))

(deftest and-with-unknown-leaf-falls-back-to-dyn
  (let [result (sut/malli-spec->type tp [:and :int :uuid])]
    (is (= (ato/intersection-type tp [(at/->GroundT tp :int 'Int) (at/Dyn tp)])
           result))))
