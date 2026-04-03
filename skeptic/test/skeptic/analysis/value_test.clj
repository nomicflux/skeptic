(ns skeptic.analysis.value-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.value :as av]))

(deftest schema-of-value-literals-test
  (testing "scalars"
    (is (= s/Int (av/schema-of-value 1)))
    (is (= (s/maybe s/Any) (av/schema-of-value nil)))
    (is (= s/Str (av/schema-of-value "x")))
    (is (= s/Keyword (av/schema-of-value :x)))
    (is (= s/Bool (av/schema-of-value true)))))

(deftest schema-of-value-collections-test
  (testing "empty list"
    (is (= [s/Any] (av/schema-of-value '()))))
  (testing "simple vector"
    (is (= [s/Int s/Int] (av/schema-of-value [1 2]))))
  (testing "nested vector with map and set"
    (let [sch (av/schema-of-value '[1 {:a 2 :b {:c #{3 4}}} 5])]
      (is (vector? sch))
      (is (= 3 (count sch)))))
  (testing "nested map"
    (let [sch (av/schema-of-value '{:a 1 :b [:z "hello" #{1 2}]
                                   :c {:d 7 :e {:f 9}}})]
      (is (map? sch))
      (is (pos? (count sch))))))
