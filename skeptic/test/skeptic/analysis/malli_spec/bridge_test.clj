(ns skeptic.analysis.malli-spec.bridge-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.types :as at]))

(deftest admit-malli-spec-accepts-malli-values-and-rejects-others
  (is (= :int (sut/admit-malli-spec :int)))
  (is (= [:=> [:cat :int] :int]
         (sut/admit-malli-spec [:=> [:cat :int] :int])))
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected Malli spec value"
                        (sut/admit-malli-spec (at/->GroundT :int 'Int)))))

(deftest malli-spec->type-converts-=>-with-primitive-leaves
  (is (= (at/->FunT [(at/->FnMethodT [(at/->GroundT :int 'Int)]
                                     (at/->GroundT :int 'Int)
                                     1
                                     false)])
         (sut/malli-spec->type [:=> [:cat :int] :int])))
  (is (= (at/->FunT [(at/->FnMethodT [(at/->GroundT :str 'Str)
                                     (at/->GroundT :keyword 'Keyword)]
                                     (at/->GroundT :bool 'Bool)
                                     2
                                     false)])
         (sut/malli-spec->type [:=> [:cat :string :keyword] :boolean])))
  (is (= (at/->FunT [(at/->FnMethodT [at/Dyn]
                                     at/Dyn
                                     1
                                     false)])
         (sut/malli-spec->type [:=> [:cat :any] :any]))))

(deftest malli-spec->type-falls-back-to-dyn-for-non-=>-shapes
  (is (= at/Dyn (sut/malli-spec->type [:map [:x :int]])))
  (is (= at/Dyn (sut/malli-spec->type [:vector :int])))
  (is (= at/Dyn (sut/malli-spec->type :int))))
