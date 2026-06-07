(ns skeptic.analysis.call-kinds.numeric-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.numeric :as anumeric]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.call-kinds.numeric :as sut]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [is-type= T tp some!]]))

(deftest integral-type-test
  (is (#'sut/integral-type? (T s/Int)))
  (is (#'sut/integral-type? (T (s/eq 1))))
  (is (not (#'sut/integral-type? (T s/Str)))))

(deftest invoke-integral-narrowing-test
  (let [args [(aat/test-typed-node :local 'x (T s/Int))
              (aat/test-typed-node :local 'y (T s/Int))]]
    (is-type= (T s/Int)
              (sut/invoke-numeric-narrow-type tp (aat/test-fn-node '+) args (mapv :type args)))))

(deftest invoke-inc-on-constant-int-narrows-to-int
  (let [args [(aat/test-typed-node :const 0 (T (s/eq 0)))]]
    (is-type= (T s/Int)
              (sut/invoke-numeric-narrow-type tp (aat/test-fn-node 'inc) args (mapv :type args)))))

(deftest static-dec-on-constant-int-narrows-to-int
  (let [args [(aat/test-typed-node :const 0 (T (s/eq 0)))]]
    (is-type= (T s/Int)
              (sut/static-numeric-narrow-type tp
                                              {:method 'dec}
                                              args
                                              (mapv :type args)
                                              {:output-type (at/NumericDyn tp)}))))

(deftest inc-on-numeric-dyn-stays-numeric-dyn
  (let [args [(aat/test-typed-node :local 'x (at/NumericDyn tp))]]
    (is-type= (at/NumericDyn tp)
              (sut/invoke-numeric-narrow-type tp (aat/test-fn-node 'inc) args (mapv :type args)))))

(deftest inc-on-non-int-numeric-literal-preserves-fine-ground
  (let [literal-type (ato/exact-value-type tp 3.5)
        args [(aat/test-typed-node :const 3.5 literal-type)]]
    (is-type= (at/->GroundT tp :double 'Double)
              (sut/invoke-numeric-narrow-type tp (aat/test-fn-node 'inc) args (mapv :type args)))))

(deftest numeric-recognizes-double-ground-test
  (let [d (at/->GroundT tp :double 'Double)]
    (testing "private numeric-type? recognizes :double keyword ground"
      (is (true? (#'sut/numeric-type? d))))
    (testing "annotate/numeric non-int-numeric-type? recognizes :double keyword ground"
      (is (true? (anumeric/non-int-numeric-type? d))))
    (testing "private numeric-ground-output-type preserves :double ground"
      (is-type= d (#'sut/numeric-ground-output-type d)))
    (testing "private inc-dec-output-type on :double returns :double"
      (is-type= d (#'sut/inc-dec-output-type d)))))

(deftest numeric-recognizes-float-ground-test
  (let [f (at/->GroundT tp :float 'Float)]
    (testing "private numeric-type? recognizes :float keyword ground"
      (is (true? (#'sut/numeric-type? f))))
    (testing "annotate/numeric non-int-numeric-type? recognizes :float keyword ground"
      (is (true? (anumeric/non-int-numeric-type? f))))
    (testing "private numeric-ground-output-type preserves :float ground"
      (is-type= f (#'sut/numeric-ground-output-type f)))
    (testing "private inc-dec-output-type on :float returns :float"
      (is-type= f (#'sut/inc-dec-output-type f)))))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil [] :clj))

(deftest invoke-numeric-narrow-type-uses-anchor-prov-test
  (testing "result prov is the anchor, not derived from arg-type provs"
    (let [int-at-other (at/->GroundT other-prov :int 'Int)
          args [(aat/test-typed-node :local 'x int-at-other)
                (aat/test-typed-node :local 'y int-at-other)]
          result (some! (sut/invoke-numeric-narrow-type tp (aat/test-fn-node '+) args (mapv :type args)))]
      (is-type= (at/->GroundT tp :int 'Int) result)
      (is (= tp (prov/of result))))))

(deftest static-numeric-narrow-type-uses-anchor-prov-test
  (testing "result prov is the anchor, not derived from arg-type provs"
    (let [int-at-other (at/->GroundT other-prov :int 'Int)
          args [(aat/test-typed-node :local 'x int-at-other)
                (aat/test-typed-node :local 'y int-at-other)]
          result (some! (sut/static-numeric-narrow-type
                         tp {:method 'add} args (mapv :type args) {:output-type (at/NumericDyn tp)}))]
      (is-type= (at/->GroundT tp :int 'Int) result)
      (is (= tp (prov/of result))))))
