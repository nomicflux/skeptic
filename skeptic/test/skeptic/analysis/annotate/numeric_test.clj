(ns skeptic.analysis.annotate.numeric-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.numeric :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.test-helpers :refer [is-type= T tp some!]]))

(deftest integral-ground-type-test
  (is (sut/integral-ground-type? (T s/Int)))
  (is (sut/integral-ground-type? (T (s/eq 1))))
  (is (not (sut/integral-ground-type? (T s/Str)))))

(deftest invoke-integral-narrowing-test
  (let [args [(aat/test-typed-node :local 'x (T s/Int))
              (aat/test-typed-node :local 'y (T s/Int))]]
    (is-type= (T s/Int)
              (sut/invoke-integral-math-narrow-type tp '+ args (mapv :type args)))))

(deftest invoke-inc-on-constant-int-narrows-to-int
  (let [args [(aat/test-typed-node :const 0 (T (s/eq 0)))]]
    (is-type= (T s/Int)
              (sut/invoke-integral-math-narrow-type tp 'inc args (mapv :type args)))))

(deftest invoke-dec-on-constant-int-narrows-to-int
  (let [args [(aat/test-typed-node :const 0 (T (s/eq 0)))]]
    (is-type= (T s/Int)
              (sut/narrow-static-numbers-output tp
                                                {:method 'dec}
                                                args
                                                (mapv :type args)
                                                {:output-type (at/NumericDyn tp)}))))

(deftest inc-on-numeric-dyn-stays-numeric-dyn
  (let [args [(aat/test-typed-node :local 'x (at/NumericDyn tp))]]
    (is-type= (at/NumericDyn tp)
              (sut/invoke-integral-math-narrow-type tp 'inc args (mapv :type args)))))

(deftest inc-on-non-int-numeric-literal-preserves-fine-ground
  (let [literal-type (ato/exact-value-type tp 3.5)
        args [(aat/test-typed-node :const 3.5 literal-type)]]
    (is-type= (at/->GroundT tp :double 'Double)
              (sut/invoke-integral-math-narrow-type tp 'inc args (mapv :type args)))))

(deftest numeric-recognizes-double-ground-test
  (let [d (at/->GroundT tp :double 'Double)]
    (testing "numeric-type? recognizes :double keyword ground"
      (is (true? (sut/numeric-type? d))))
    (testing "non-int-numeric-type? recognizes :double keyword ground"
      (is (true? (sut/non-int-numeric-type? d))))
    (testing "numeric-ground-output-type preserves :double ground"
      (is-type= d (#'sut/numeric-ground-output-type d)))
    (testing "inc-dec-output-type on :double returns :double"
      (is-type= d (sut/inc-dec-output-type d)))))

(deftest numeric-recognizes-float-ground-test
  (let [f (at/->GroundT tp :float 'Float)]
    (testing "numeric-type? recognizes :float keyword ground"
      (is (true? (sut/numeric-type? f))))
    (testing "non-int-numeric-type? recognizes :float keyword ground"
      (is (true? (sut/non-int-numeric-type? f))))
    (testing "numeric-ground-output-type preserves :float ground"
      (is-type= f (#'sut/numeric-ground-output-type f)))
    (testing "inc-dec-output-type on :float returns :float"
      (is-type= f (sut/inc-dec-output-type f)))))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil [] :clj))

(deftest invoke-integral-math-narrow-type-uses-anchor-prov-test
  (testing "result prov is the anchor, not derived from arg-type provs"
    (let [int-at-other (at/->GroundT other-prov :int 'Int)
          args [(aat/test-typed-node :local 'x int-at-other)
                (aat/test-typed-node :local 'y int-at-other)]
          result (some! (sut/invoke-integral-math-narrow-type tp '+ args (mapv :type args)))]
      (is-type= (at/->GroundT tp :int 'Int) result)
      (is (= tp (prov/of result))))))

(deftest narrow-static-numbers-output-uses-anchor-prov-test
  (testing "result prov is the anchor, not derived from arg-type provs"
    (let [int-at-other (at/->GroundT other-prov :int 'Int)
          args [(aat/test-typed-node :local 'x int-at-other)
                (aat/test-typed-node :local 'y int-at-other)]
          result (some! (sut/narrow-static-numbers-output
                         tp {:method 'add} args (mapv :type args) {:output-type (at/NumericDyn tp)}))]
      (is-type= (at/->GroundT tp :int 'Int) result)
      (is (= tp (prov/of result))))))
