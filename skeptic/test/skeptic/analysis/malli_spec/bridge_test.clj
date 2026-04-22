(ns skeptic.analysis.malli-spec.bridge-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest admit-malli-spec-accepts-malli-values-and-rejects-others
  (is (= :int (sut/admit-malli-spec :int)))
  (is (= [:=> [:cat :int] :int]
         (sut/admit-malli-spec [:=> [:cat :int] :int])))
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected Malli spec value"
                        (sut/admit-malli-spec (at/->GroundT tp :int 'Int)))))

(deftest malli-spec->type-converts-=>-with-primitive-leaves
  (is (at/type=? (at/->FunT tp [(at/->FnMethodT tp [(at/->GroundT tp :int 'Int)]
                                     (at/->GroundT tp :int 'Int)
                                     1
                                     false
                                     '[arg0])])
         (sut/malli-spec->type tp [:=> [:cat :int] :int])))
  (is (at/type=? (at/->FunT tp [(at/->FnMethodT tp [(at/->GroundT tp :str 'Str)
                                     (at/->GroundT tp :keyword 'Keyword)]
                                     (at/->GroundT tp :bool 'Bool)
                                     2
                                     false
                                     '[arg0 arg1])])
         (sut/malli-spec->type tp [:=> [:cat :string :keyword] :boolean])))
  (is (at/type=? (at/->FunT tp [(at/->FnMethodT tp [(at/Dyn tp)]
                                     (at/Dyn tp)
                                     1
                                     false
                                     '[arg0])])
         (sut/malli-spec->type tp [:=> [:cat :any] :any]))))

(deftest malli-spec->type-falls-back-to-dyn-for-non-=>-shapes
  (is (at/type=? (at/Dyn tp) (sut/malli-spec->type tp [:map [:x :int]])))
  (is (at/type=? (at/Dyn tp) (sut/malli-spec->type tp [:vector :int])))
  (is (at/type=? (at/->GroundT tp :int 'Int) (sut/malli-spec->type tp :int))))
