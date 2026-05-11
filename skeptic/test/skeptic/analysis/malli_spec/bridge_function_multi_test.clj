(ns skeptic.analysis.malli-spec.bridge-function-multi-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(defn- method
  [inputs output min-arity]
  (at/->FnMethodT tp inputs output min-arity false
                  (mapv #(symbol (str "arg" %)) (range min-arity))))

(deftest function-multi-emits-funt-with-method-per-arm
  (is (= (at/->FunT tp [(method [(at/->GroundT tp :int 'Int)]
                                (at/->GroundT tp :int 'Int)
                                1)
                        (method [(at/->GroundT tp :int 'Int)
                                 (at/->GroundT tp :int 'Int)]
                                (at/->GroundT tp :int 'Int)
                                2)])
         (sut/malli-spec->type
          tp
          [:function
           [:=> [:cat :int] :int]
           [:=> [:cat :int :int] :int]]))))

(deftest function-multi-with-properties-still-admits
  (is (= (at/->FunT tp [(method [(at/->GroundT tp :int 'Int)]
                                (at/->GroundT tp :int 'Int)
                                1)])
         (sut/malli-spec->type
          tp
          [:function {} [:=> [:cat :int] :int]]))))

(deftest single-arity-equal-arrow-still-emits-funt-with-one-method
  (is (= (at/->FunT tp [(method [(at/->GroundT tp :int 'Int)]
                                (at/->GroundT tp :int 'Int)
                                1)])
         (sut/malli-spec->type tp [:=> [:cat :int] :int]))))

(deftest zero-arity-equal-arrow-handles-collapsed-cat-keyword
  (is (= (at/->FunT tp [(method [] (at/->GroundT tp :int 'Int) 0)])
         (sut/malli-spec->type tp [:=> [:cat] :int]))))

(deftest zero-arity-arm-in-multi-still-admits
  (is (= (at/->FunT tp [(method [] (at/->GroundT tp :int 'Int) 0)
                        (method [(at/->GroundT tp :int 'Int)]
                                (at/->GroundT tp :int 'Int)
                                1)])
         (sut/malli-spec->type tp [:function
                                   [:=> [:cat] :int]
                                   [:=> [:cat :int] :int]]))))
