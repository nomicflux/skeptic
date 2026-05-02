(ns skeptic.analysis.value-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value-check :as avc]
            [skeptic.test-helpers :refer [is-type= tp]]))

(deftest contains-key-type-classification-regression-test
  (is (= :unknown
         (avc/contains-key-type-classification (ab/schema->type tp {s/Keyword s/Any}) :a)))
  (is (= :always
         (avc/contains-key-type-classification (ab/schema->type tp {:a s/Int s/Keyword s/Any}) :a)))
  (is (= :unknown
         (avc/contains-key-type-classification (ab/schema->type tp {:a s/Int s/Keyword s/Any}) :b))))

(deftest semantic-value-satisfies-type-regression-test
  (is (avc/value-satisfies-type? [1 2 3] (ab/schema->type tp [s/Int])))
  (is (avc/value-satisfies-type? [1 2 3] (ab/schema->type tp [s/Int s/Int s/Int])))
  (is (not (avc/value-satisfies-type? [1 2 3] (ab/schema->type tp [s/Int s/Int])))))

(deftest contains-key-classification-on-conditional-test
  (let [has-a (ab/schema->type tp {:a s/Int})
        has-b (ab/schema->type tp {:b s/Int})
        cond-type (at/->ConditionalT tp [[#(contains? % :a) has-a nil]
                                         [#(contains? % :b) has-b nil]])]
    (testing "key present in every branch -> :always"
      (let [both (at/->ConditionalT tp [[#(contains? % :a) has-a nil]
                                        [#(contains? % :a) has-a nil]])]
        (is (= :always (avc/contains-key-type-classification both :a)))))
    (testing "key present in some branches -> :unknown"
      (is (= :unknown (avc/contains-key-type-classification cond-type :a))))
    (testing "key absent from every branch -> :never"
      (is (= :never (avc/contains-key-type-classification cond-type :z))))))

(deftest refine-by-contains-key-on-conditional-test
  (let [has-a (ab/schema->type tp {:a s/Int})
        has-b (ab/schema->type tp {:b s/Int})
        cond-type (at/->ConditionalT tp [[#(contains? % :a) has-a nil]
                                         [#(contains? % :b) has-b nil]])
        refined-true (amo/refine-by-contains-key cond-type :a true)
        refined-false (amo/refine-by-contains-key cond-type :a false)]
    (is-type= has-a refined-true)
    (is-type= has-b refined-false)))

(deftest value-satisfies-conditional-test
  (let [has-a (ab/schema->type tp {:a s/Int})
        has-b (ab/schema->type tp {:b s/Int})
        cond-type (at/->ConditionalT tp [[#(contains? % :a) has-a nil]
                                         [#(contains? % :b) has-b nil]])]
    (is (avc/value-satisfies-type? {:a 1} cond-type))
    (is (avc/value-satisfies-type? {:b 2} cond-type))
    (is (not (avc/value-satisfies-type? {:c 3} cond-type)))
    (testing "value matching pred but not branch type -> false"
      (is (not (avc/value-satisfies-type? {:a "not-int"} cond-type))))))

(deftest numeric-ground-type-non-numeric-grounds-return-false
  (testing "Keyword ground returns false, not nil"
    (is (false? (avc/numeric-ground-type? (ab/schema->type tp s/Keyword)))))
  (testing "Str ground returns false"
    (is (false? (avc/numeric-ground-type? (ab/schema->type tp s/Str)))))
  (testing "Bool ground returns false"
    (is (false? (avc/numeric-ground-type? (ab/schema->type tp s/Bool)))))
  (testing "Int ground returns true"
    (is (true? (avc/numeric-ground-type? (ab/schema->type tp s/Int))))))
