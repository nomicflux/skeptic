(ns skeptic.analysis.annotate.numeric-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.numeric :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest integral-ground-type-test
  (is (sut/integral-ground-type? (atst/T s/Int)))
  (is (sut/integral-ground-type? (atst/T (s/eq 1))))
  (is (not (sut/integral-ground-type? (atst/T s/Str)))))

(deftest invoke-integral-narrowing-test
  (let [args [(aat/test-typed-node :local 'x (atst/T s/Int))
              (aat/test-typed-node :local 'y (atst/T s/Int))]]
    (is (at/type=? (atst/T s/Int)
           (sut/invoke-integral-math-narrow-type tp '+ args (mapv :type args))))))

(deftest invoke-inc-on-constant-int-narrows-to-int
  (let [args [(aat/test-typed-node :const 0 (atst/T (s/eq 0)))]]
    (is (at/type=? (atst/T s/Int)
           (sut/invoke-integral-math-narrow-type tp 'inc args (mapv :type args))))))

(deftest invoke-dec-on-constant-int-narrows-to-int
  (let [args [(aat/test-typed-node :const 0 (atst/T (s/eq 0)))]]
    (is (at/type=? (atst/T s/Int)
           (sut/narrow-static-numbers-output tp
                                             {:method 'dec}
                                             args
                                             (mapv :type args)
                                             {:output-type (at/NumericDyn tp)})))))

(deftest inc-on-numeric-dyn-stays-numeric-dyn
  (let [args [(aat/test-typed-node :local 'x (at/NumericDyn tp))]]
    (is (at/type=? (at/NumericDyn tp)
           (sut/invoke-integral-math-narrow-type tp 'inc args (mapv :type args))))))

(deftest inc-on-non-int-numeric-literal-preserves-fine-ground
  (let [literal-type (ato/exact-value-type tp 3.5)
        args [(aat/test-typed-node :const 3.5 literal-type)]]
    (is (at/type=? (at/->GroundT tp {:class java.lang.Double} 'Double)
           (sut/invoke-integral-math-narrow-type tp 'inc args (mapv :type args))))))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil))

(deftest invoke-integral-math-narrow-type-uses-anchor-prov-test
  (testing "result prov is the anchor, not derived from arg-type provs"
    (let [int-at-other (at/->GroundT other-prov :int 'Int)
          args [(aat/test-typed-node :local 'x int-at-other)
                (aat/test-typed-node :local 'y int-at-other)]
          result (sut/invoke-integral-math-narrow-type tp '+ args (mapv :type args))]
      (is (at/type=? (at/->GroundT tp :int 'Int) result))
      (is (= tp (prov/of result))))))

(deftest narrow-static-numbers-output-uses-anchor-prov-test
  (testing "result prov is the anchor, not derived from arg-type provs"
    (let [int-at-other (at/->GroundT other-prov :int 'Int)
          args [(aat/test-typed-node :local 'x int-at-other)
                (aat/test-typed-node :local 'y int-at-other)]
          result (sut/narrow-static-numbers-output
                  tp {:method 'add} args (mapv :type args) {:output-type (at/NumericDyn tp)})]
      (is (at/type=? (at/->GroundT tp :int 'Int) result))
      (is (= tp (prov/of result))))))
