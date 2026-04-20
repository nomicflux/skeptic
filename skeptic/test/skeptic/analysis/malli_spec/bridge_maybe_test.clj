(ns skeptic.analysis.malli-spec.bridge-maybe-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.types :as at]))

(deftest maybe-int-converts-to-maybe-t
  (is (= (at/->MaybeT (at/->GroundT :int 'Int))
         (sut/malli-spec->type [:maybe :int]))))

(deftest nested-maybe-documents-observed-shape
  (is (= (at/->MaybeT (at/->MaybeT (at/->GroundT :int 'Int)))
         (sut/malli-spec->type [:maybe [:maybe :int]]))))

(deftest =>-with-maybe-input-returns-fun-t-with-maybe-t-input
  (is (= (at/->FunT [(at/->FnMethodT [(at/->MaybeT (at/->GroundT :int 'Int))]
                                     (at/->GroundT :int 'Int)
                                     1
                                     false)])
         (sut/malli-spec->type [:=> [:cat [:maybe :int]] :int]))))

(deftest maybe-unknown-leaf-falls-back-to-dyn
  (is (= (at/->MaybeT at/Dyn)
         (sut/malli-spec->type [:maybe :uuid]))))
