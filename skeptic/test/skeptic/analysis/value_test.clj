(ns skeptic.analysis.value-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest type-of-value-literals-test
  (testing "scalars"
    (is (= (T s/Int) (av/type-of-value 1)))
    (is (= (T (s/eq nil)) (av/type-of-value nil)))
    (is (= (T s/Str) (av/type-of-value "x")))
    (is (= (T s/Keyword) (av/type-of-value :x)))
    (is (= (T s/Bool) (av/type-of-value true))))
  (testing "fine numeric grounds are preserved"
    (let [double-type (av/type-of-value 3.5)
          float-type (av/type-of-value (float 3.5))
          bigdec-type (av/type-of-value 3.5M)
          ratio-type (av/type-of-value 7/2)]
      (is (= {:class java.lang.Double} (:ground double-type)))
      (is (= {:class java.lang.Float} (:ground float-type)))
      (is (= {:class java.math.BigDecimal} (:ground bigdec-type)))
      (is (= {:class clojure.lang.Ratio} (:ground ratio-type)))
      (is (not= (:ground bigdec-type) (:ground float-type))))))

(deftest type-of-value-collections-test
  (testing "empty list"
    (is (= (at/->SeqT [at/Dyn] true) (av/type-of-value '()))))
  (testing "simple vector"
    (is (= (T [s/Int s/Int]) (av/type-of-value [1 2]))))
  (testing "nested vector with map and set"
    (let [type (av/type-of-value '[1 {:a 2 :b {:c #{3 4}}} 5])]
      (is (= (at/->VectorT [(T s/Int)
                            (at/->MapT {(at/->ValueT (T s/Keyword) :a) (at/->ValueT (T s/Int) 2)
                                        (at/->ValueT (T s/Keyword) :b) (at/->ValueT (at/->MapT {(at/->ValueT (T s/Keyword) :c)
                                                                                                   (at/->ValueT (at/->SetT #{(T s/Int)} true) #{3 4})})
                                                                                 {:c #{3 4}})})
                            (T s/Int)]
                           false)
             type))))
  (testing "nested map"
    (let [type (av/type-of-value '{:a 1 :b [:z "hello" #{1 2}]
                                  :c {:d 7 :e {:f 9}}})]
      (is (= (ato/normalize-type
              {(at/->ValueT (T s/Keyword) :a) (at/->ValueT (T s/Int) 1)
               (at/->ValueT (T s/Keyword) :b) (at/->ValueT (at/->VectorT [(T s/Keyword)
                                                                           (T s/Str)
                                                                           (at/->SetT #{(T s/Int)} true)]
                                                                          false)
                                                            [:z "hello" #{1 2}])
               (at/->ValueT (T s/Keyword) :c) (at/->ValueT (ato/normalize-type {(at/->ValueT (T s/Keyword) :d) (at/->ValueT (T s/Int) 7)
                                                                                 (at/->ValueT (T s/Keyword) :e) (at/->ValueT (ato/normalize-type {(at/->ValueT (T s/Keyword) :f)
                                                                                                                                                 (at/->ValueT (T s/Int) 9)})
                                                                                                                          {:f 9})})
                                                            {:d 7 :e {:f 9}})})
             type)))))
