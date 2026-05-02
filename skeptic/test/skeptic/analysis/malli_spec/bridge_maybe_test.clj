(ns skeptic.analysis.malli-spec.bridge-maybe-test
  (:require [clojure.test :refer [deftest]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.test-helpers :refer [is-type= tp]]))

(deftest maybe-int-converts-to-maybe-t
  (is-type= (at/->MaybeT tp (at/->GroundT tp :int 'Int))
            (sut/malli-spec->type tp [:maybe :int])))

(deftest nested-maybe-documents-observed-shape
  (is-type= (at/->MaybeT tp (at/->MaybeT tp (at/->GroundT tp :int 'Int)))
            (sut/malli-spec->type tp [:maybe [:maybe :int]])))

(deftest =>-with-maybe-input-returns-fun-t-with-maybe-t-input
  (is-type= (at/->FunT tp [(at/->FnMethodT tp [(at/->MaybeT tp (at/->GroundT tp :int 'Int))]
                                           (at/->GroundT tp :int 'Int)
                                           1
                                           false
                                           '[arg0])])
            (sut/malli-spec->type tp [:=> [:cat [:maybe :int]] :int])))

(deftest maybe-unknown-leaf-falls-back-to-dyn
  (is-type= (at/->MaybeT tp (at/Dyn tp))
            (sut/malli-spec->type tp [:maybe :uuid])))
