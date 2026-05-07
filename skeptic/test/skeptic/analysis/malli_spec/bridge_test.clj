(ns skeptic.analysis.malli-spec.bridge-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.test-helpers :refer [is-type= tp]]
            [skeptic.analysis.types :as at]))

(deftest admit-malli-spec-accepts-malli-values-and-rejects-others
  (is (= :int (sut/admit-malli-spec :int)))
  (is (= [:=> [:cat :int] :int]
         (sut/admit-malli-spec [:=> [:cat :int] :int])))
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected Malli spec value"
                        (sut/admit-malli-spec (at/->GroundT tp :int 'Int)))))

(deftest malli-spec->type-converts-=>-with-primitive-leaves
  (is-type= (at/->FunT tp [(at/->FnMethodT tp [(at/->GroundT tp :int 'Int)]
                                           (at/->GroundT tp :int 'Int)
                                           1
                                           false
                                           '[arg0])])
            (sut/malli-spec->type tp [:=> [:cat :int] :int]))
  (is-type= (at/->FunT tp [(at/->FnMethodT tp [(at/->GroundT tp :str 'Str)
                                               (at/->GroundT tp :keyword 'Keyword)]
                                           (at/->GroundT tp :bool 'Bool)
                                           2
                                           false
                                           '[arg0 arg1])])
            (sut/malli-spec->type tp [:=> [:cat :string :keyword] :boolean]))
  (is-type= (at/->FunT tp [(at/->FnMethodT tp [(at/Dyn tp)]
                                           (at/Dyn tp)
                                           1
                                           false
                                           '[arg0])])
            (sut/malli-spec->type tp [:=> [:cat :any] :any])))

(deftest malli-spec->type-falls-back-to-dyn-for-non-=>-shapes
  (is-type= (at/Dyn tp) (sut/malli-spec->type tp [:map [:x :int]]))
  (is-type= (at/Dyn tp) (sut/malli-spec->type tp [:vector :int]))
  (is-type= (at/->GroundT tp :int 'Int) (sut/malli-spec->type tp :int)))

(deftest malli-spec->type-handles-extended-primitive-leaves
  (is-type= (ato/exact-value-type tp nil) (sut/malli-spec->type tp :nil))
  (is-type= (at/->GroundT tp :symbol 'Symbol) (sut/malli-spec->type tp :symbol))
  (is-type= (at/->GroundT tp :double 'Double) (sut/malli-spec->type tp :double))
  (is-type= (at/->GroundT tp :keyword 'Keyword) (sut/malli-spec->type tp :qualified-keyword))
  (is-type= (at/->GroundT tp :symbol 'Symbol) (sut/malli-spec->type tp :qualified-symbol)))

(deftest malli-spec->type-uuid-falls-back-to-dyn
  (is-type= (at/Dyn tp) (sut/malli-spec->type tp :uuid)))

(deftest malli-rejects-out-of-registry-keywords
  (doseq [k [:char :pos-int :neg-int :nat-int]]
    (is (thrown-with-msg? IllegalArgumentException
                          #"Expected Malli spec value"
                          (sut/malli-spec->type tp k)))))

(deftest malli-spec->type-handles-float-leaf
  (is-type= (at/->GroundT tp :float 'Float) (sut/malli-spec->type tp :float)))
