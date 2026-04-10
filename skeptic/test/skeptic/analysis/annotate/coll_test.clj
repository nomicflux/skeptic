(ns skeptic.analysis.annotate.coll-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.annotate.coll :as sut]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.types :as at]))

(deftest collection-shape-helpers-test
  (let [vec-type (atst/T [s/Int s/Int])
        seq-type (atst/T [s/Int])]
    (is (sut/vec-homogeneous-items? [(atst/T s/Int) (atst/T s/Int)]))
    (is (= (atst/T s/Int) (sut/vector-slot-type vec-type 0)))
    (is (= (atst/T s/Int) (sut/coll-first-type vec-type)))
    (is (= (atst/T s/Int) (sut/coll-last-type seq-type)))
    (is (= (atst/T [s/Int]) (sut/coll-take-prefix-type vec-type 1)))))

(deftest collection-output-helpers-test
  (let [args [{:type (atst/T [s/Int])} {:type (atst/T [s/Int])}]]
    (is (at/seq-type? (sut/concat-output-type args)))
    (is (some? (sut/into-output-type args)))))
