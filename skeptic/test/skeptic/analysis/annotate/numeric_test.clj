(ns skeptic.analysis.annotate.numeric-test
  (:require [clojure.test :refer [deftest is]]
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
  (let [fn-node (aat/test-fn-node '+)
        args [(aat/test-typed-node :local 'x (atst/T s/Int))
              (aat/test-typed-node :local 'y (atst/T s/Int))]]
    (is (= (atst/T s/Int)
           (sut/invoke-integral-math-narrow-type fn-node args (mapv :type args))))))

(deftest invoke-inc-on-constant-int-narrows-to-int
  (let [fn-node (aat/test-fn-node 'inc)
        args [(aat/test-typed-node :const 0 (atst/T (s/eq 0)))]]
    (is (= (atst/T s/Int)
           (sut/invoke-integral-math-narrow-type fn-node args (mapv :type args))))))

(deftest invoke-dec-on-constant-int-narrows-to-int
  (let [args [(aat/test-typed-node :const 0 (atst/T (s/eq 0)))]]
    (is (= (atst/T s/Int)
           (sut/narrow-static-numbers-output {:method 'dec}
                                             args
                                             (mapv :type args)
                                             {:output-type (at/NumericDyn tp)})))))

(deftest inc-on-numeric-dyn-stays-numeric-dyn
  (let [fn-node (aat/test-fn-node 'inc)
        args [(aat/test-typed-node :local 'x (at/NumericDyn tp))]]
    (is (= (at/NumericDyn tp)
           (sut/invoke-integral-math-narrow-type fn-node args (mapv :type args))))))

(deftest inc-on-non-int-numeric-literal-preserves-fine-ground
  (let [fn-node (aat/test-fn-node 'inc)
        literal-type (ato/exact-value-type tp 3.5)
        args [(aat/test-typed-node :const 3.5 literal-type)]]
    (is (= (at/->GroundT tp {:class java.lang.Double} 'Double)
           (sut/invoke-integral-math-narrow-type fn-node args (mapv :type args))))))
