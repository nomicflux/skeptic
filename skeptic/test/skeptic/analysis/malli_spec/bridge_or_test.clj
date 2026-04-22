(ns skeptic.analysis.malli-spec.bridge-or-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest or-with-two-primitive-members
  (is (= (ato/union-type tp [(at/->GroundT tp :int 'Int) (at/->GroundT tp :str 'Str)])
         (sut/malli-spec->type tp [:or :int :string]))))

(deftest or-with-single-member-short-circuits
  (is (= (ato/union-type tp [(at/->GroundT tp :int 'Int)])
         (sut/malli-spec->type tp [:or :int]))))

(deftest or-with-maybe-member-documents-observed-shape
  (let [expected (ato/union-type tp [(at/->MaybeT tp (at/->GroundT tp :int 'Int))
                                  (at/->GroundT tp :str 'Str)])]
    (is (= expected (sut/malli-spec->type tp [:or [:maybe :int] :string])))))

(deftest or-with-unknown-leaf-falls-back-to-dyn
  (let [result (sut/malli-spec->type tp [:or :int :uuid])]
    (is (= (ato/union-type tp [(at/->GroundT tp :int 'Int) (at/Dyn tp)])
           result))))
