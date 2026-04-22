(ns skeptic.analysis.malli-spec.bridge-maybe-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest maybe-int-converts-to-maybe-t
  (is (= (at/->MaybeT tp (at/->GroundT tp :int 'Int))
         (sut/malli-spec->type tp [:maybe :int]))))

(deftest nested-maybe-documents-observed-shape
  (is (= (at/->MaybeT tp (at/->MaybeT tp (at/->GroundT tp :int 'Int)))
         (sut/malli-spec->type tp [:maybe [:maybe :int]]))))

(deftest =>-with-maybe-input-returns-fun-t-with-maybe-t-input
  (is (= (at/->FunT tp [(at/->FnMethodT tp [(at/->MaybeT tp (at/->GroundT tp :int 'Int))]
                                     (at/->GroundT tp :int 'Int)
                                     1
                                     false
                                     '[arg0])])
         (sut/malli-spec->type tp [:=> [:cat [:maybe :int]] :int]))))

(deftest maybe-unknown-leaf-falls-back-to-dyn
  (is (= (at/->MaybeT tp (at/Dyn tp))
         (sut/malli-spec->type tp [:maybe :uuid]))))
