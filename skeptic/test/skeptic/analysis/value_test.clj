(ns skeptic.analysis.value-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [T tp]]))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil))

(deftest join-container-owns-prov-test
  (testing "empty item seq does not crash; result carries anchor prov"
    (let [result (av/join tp [])]
      (is (= tp (prov/of result)))))
  (testing "non-empty items carry their own provs, but result's prov is the anchor"
    (let [int-t (at/->GroundT other-prov :int 'Int)
          str-t (at/->GroundT other-prov :str 'Str)
          result (av/join tp [int-t str-t])]
      (is (= tp (prov/of result))))))

(deftest type-of-value-literals-test
  (testing "scalars"
    (is (= (T s/Int) (av/type-of-value tp 1)))
    (is (= (T (s/eq nil)) (av/type-of-value tp nil)))
    (is (= (T s/Str) (av/type-of-value tp "x")))
    (is (= (T s/Keyword) (av/type-of-value tp :x)))
    (is (= (T s/Bool) (av/type-of-value tp true))))
  (testing "fine numeric grounds are preserved"
    (let [double-type (av/type-of-value tp 3.5)
          float-type (av/type-of-value tp (float 3.5))
          bigdec-type (av/type-of-value tp 3.5M)
          ratio-type (av/type-of-value tp 7/2)]
      (is (= {:class java.lang.Double} (:ground double-type)))
      (is (= {:class java.lang.Float} (:ground float-type)))
      (is (= {:class java.math.BigDecimal} (:ground bigdec-type)))
      (is (= {:class clojure.lang.Ratio} (:ground ratio-type)))
      (is (not= (:ground bigdec-type) (:ground float-type))))))

(deftest type-of-value-collections-test
  (testing "empty list"
    (is (at/type=? (at/->SeqT tp [(at/Dyn tp)] true) (av/type-of-value tp '()))))
  (testing "simple vector"
    (is (at/type=? (T [s/Int s/Int]) (av/type-of-value tp [1 2]))))
  (testing "nested vector with map and set"
    (let [type (av/type-of-value tp '[1 {:a 2 :b {:c #{3 4}}} 5])]
      (is (at/type=? (at/->VectorT tp [(T s/Int)
                            (at/->MapT tp {(at/->ValueT tp (T s/Keyword) :a) (at/->ValueT tp (T s/Int) 2)
                                        (at/->ValueT tp (T s/Keyword) :b) (at/->ValueT tp (at/->MapT tp {(at/->ValueT tp (T s/Keyword) :c)
                                                                                                   (at/->ValueT tp (at/->SetT tp #{(T s/Int)} true) #{3 4})})
                                                                                 {:c #{3 4}})})
                            (T s/Int)]
                           false)
             type))))
  (testing "nested map"
    (let [type (av/type-of-value tp '{:a 1 :b [:z "hello" #{1 2}]
                                  :c {:d 7 :e {:f 9}}})]
      (is (at/type=? (ato/normalize-type tp {(at/->ValueT tp (T s/Keyword) :a) (at/->ValueT tp (T s/Int) 1)
               (at/->ValueT tp (T s/Keyword) :b) (at/->ValueT tp (at/->VectorT tp [(T s/Keyword)
                                                                           (T s/Str)
                                                                           (at/->SetT tp #{(T s/Int)} true)]
                                                                          false)
                                                            [:z "hello" #{1 2}])
               (at/->ValueT tp (T s/Keyword) :c) (at/->ValueT tp (ato/normalize-type tp {(at/->ValueT tp (T s/Keyword) :d) (at/->ValueT tp (T s/Int) 7)
                                                                                 (at/->ValueT tp (T s/Keyword) :e) (at/->ValueT tp (ato/normalize-type tp {(at/->ValueT tp (T s/Keyword) :f)
                                                                                                                                                 (at/->ValueT tp (T s/Int) 9)})
                                                                                                                          {:f 9})})
                                                            {:d 7 :e {:f 9}})})
             type)))))

(deftest type-of-value-vector-arm-threads-refs-test
  (testing "vector input threads item provs into result's refs"
    (let [result (av/type-of-value tp [1 2])
          refs (:refs (:prov result))]
      (is (= 2 (count refs)))
      (is (every? #(= tp %) refs)))))

(deftest type-of-value-seq-arm-threads-refs-test
  (testing "seq input threads joined element prov into result's refs"
    (let [result (av/type-of-value tp '(1 2 3))
          refs (:refs (:prov result))]
      (is (= 1 (count refs))))))

(deftest type-of-value-set-arm-threads-refs-test
  (testing "set input threads joined element prov into result's refs"
    (let [result (av/type-of-value tp #{1 2})
          refs (:refs (:prov result))]
      (is (= 1 (count refs))))))

(deftest map-value-type-threads-refs-test
  (testing "map value threads flattened k/v provs into result's refs"
    (let [result (av/map-value-type tp {:a 1})
          refs (:refs (:prov result))]
      (is (= 2 (count refs))))))
