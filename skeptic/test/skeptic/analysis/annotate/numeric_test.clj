(ns skeptic.analysis.annotate.numeric-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.annotate.numeric :as sut]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis-test :as atst]))

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
