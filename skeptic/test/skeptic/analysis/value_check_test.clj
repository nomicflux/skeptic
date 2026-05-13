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
  (is (avc/value-satisfies-type? [1 2 3] (ab/schema->type tp [(s/one s/Int 'a) (s/one s/Int 'b) (s/one s/Int 'c)])))
  (is (not (avc/value-satisfies-type? [1 2 3] (ab/schema->type tp [(s/one s/Int 'a) (s/one s/Int 'b)])))))

(deftest contains-key-classification-on-conditional-test
  (let [has-a (ab/schema->type tp {:a s/Int})
        has-b (ab/schema->type tp {:b s/Int})
        cond-type (at/->ConditionalT tp [(at/->ConditionalBranch #(contains? % :a) has-a nil nil)
                                          (at/->ConditionalBranch #(contains? % :b) has-b nil nil)])]
    (testing "key present in every branch -> :always"
      (let [both (at/->ConditionalT tp [(at/->ConditionalBranch #(contains? % :a) has-a nil nil)
                                         (at/->ConditionalBranch #(contains? % :a) has-a nil nil)])]
        (is (= :always (avc/contains-key-type-classification both :a)))))
    (testing "key present in some branches -> :unknown"
      (is (= :unknown (avc/contains-key-type-classification cond-type :a))))
    (testing "key absent from every branch -> :never"
      (is (= :never (avc/contains-key-type-classification cond-type :z))))))

(deftest refine-by-contains-key-on-conditional-test
  (let [has-a (ab/schema->type tp {:a s/Int})
        has-b (ab/schema->type tp {:b s/Int})
        cond-type (at/->ConditionalT tp [(at/->ConditionalBranch #(contains? % :a) has-a nil nil)
                                          (at/->ConditionalBranch #(contains? % :b) has-b nil nil)])
        refined-true (amo/refine-by-contains-key cond-type :a true)
        refined-false (amo/refine-by-contains-key cond-type :a false)]
    (is-type= has-a refined-true)
    (is-type= has-b refined-false)))

(deftest value-satisfies-conditional-test
  (let [has-a (ab/schema->type tp {:a s/Int})
        has-b (ab/schema->type tp {:b s/Int})
        cond-type (at/->ConditionalT tp [(at/->ConditionalBranch #(contains? % :a) has-a nil nil)
                                          (at/->ConditionalBranch #(contains? % :b) has-b nil nil)])]
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

(deftest double-keyword-ground-test
  (let [d (at/->GroundT tp :double 'Double)]
    (testing "numeric-ground-type? recognizes :double keyword ground"
      (is (true? (avc/numeric-ground-type? d))))
    (testing "ground-accepts-value? :double accepts doubles, rejects ints/strings"
      (is (true? (avc/ground-accepts-value? d 1.5)))
      (is (false? (avc/ground-accepts-value? d 1)))
      (is (false? (avc/ground-accepts-value? d "x"))))))

(deftest leaf-overlap-double-test
  (let [d (at/->GroundT tp :double 'Double)
        i (at/->GroundT tp :int 'Int)
        num-class (at/->GroundT tp {:class Number} 'Number)
        obj-class (at/->GroundT tp {:class Object} 'Object)]
    (testing ":double overlaps with :double"
      (is (true? (avc/leaf-overlap? d d))))
    (testing ":double overlaps with Number-class target"
      (is (true? (avc/leaf-overlap? d num-class))))
    (testing ":double overlaps with Object-class target"
      (is (true? (avc/leaf-overlap? d obj-class))))
    (testing ":int does not overlap with :double"
      (is (false? (avc/leaf-overlap? i d))))))

(deftest float-keyword-ground-test
  (let [f (at/->GroundT tp :float 'Float)]
    (testing "numeric-ground-type? recognizes :float keyword ground"
      (is (true? (avc/numeric-ground-type? f))))
    (testing "ground-accepts-value? :float accepts floats, rejects ints/strings/doubles"
      (is (true? (avc/ground-accepts-value? f (float 1.5))))
      (is (false? (avc/ground-accepts-value? f 1)))
      (is (false? (avc/ground-accepts-value? f 1.5)))
      (is (false? (avc/ground-accepts-value? f "x"))))))

(deftest leaf-overlap-float-test
  (let [f (at/->GroundT tp :float 'Float)
        d (at/->GroundT tp :double 'Double)
        i (at/->GroundT tp :int 'Int)
        num-class (at/->GroundT tp {:class Number} 'Number)
        obj-class (at/->GroundT tp {:class Object} 'Object)]
    (testing ":float overlaps with :float"
      (is (true? (avc/leaf-overlap? f f))))
    (testing ":float overlaps with Number-class target"
      (is (true? (avc/leaf-overlap? f num-class))))
    (testing ":float overlaps with Object-class target"
      (is (true? (avc/leaf-overlap? f obj-class))))
    (testing ":int does not overlap with :float"
      (is (false? (avc/leaf-overlap? i f))))
    (testing ":double does not overlap with :float"
      (is (false? (avc/leaf-overlap? d f))))))
