(ns skeptic.analysis.narrowing-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [schema.core :as s]))

(deftest classify-leaf-test
  (testing "ground types"
    (is (= :matches (an/classify-leaf-for-predicate? {:pred :string?} (atst/T s/Str))))
    (is (= :does-not-match (an/classify-leaf-for-predicate? {:pred :string?} (atst/T s/Int)))))
  (testing "fn?"
    (is (= :matches (an/classify-leaf-for-predicate? {:pred :fn?} (at/->FunT []))))))

(deftest partition-string-predicate-union-test
  (let [u (ato/union-type #{(atst/T s/Str) (atst/T s/Int)})
        pos (an/partition-type-for-predicate u {:pred :string?} true)
        neg (an/partition-type-for-predicate u {:pred :string?} false)]
    (is (= (atst/T s/Str) pos))
    (is (= (atst/T s/Int) neg))))

(deftest partition-maybe-nil-some-test
  (let [m (at/->MaybeT (atst/T s/Int))
        npos (an/partition-type-for-predicate m {:pred :nil?} true)
        nneg (an/partition-type-for-predicate m {:pred :nil?} false)
        spos (an/partition-type-for-predicate m {:pred :some?} true)
        sneg (an/partition-type-for-predicate m {:pred :some?} false)]
    (is (at/value-type? npos))
    (is (nil? (:value npos)))
    (is (= (atst/T s/Int) nneg))
    (is (= (atst/T s/Int) spos))
    (is (at/value-type? sneg))
    (is (nil? (:value sneg)))))

(deftest partition-dyn-unchanged-test
  (is (= at/Dyn (an/partition-type-for-predicate at/Dyn {:pred :string?} true))))

(deftest apply-truthy-local-test
  (let [u (ato/union-type #{(at/->ValueT (at/->GroundT :bool 'Bool) false)
                            (atst/T s/Int)})]
    (is (= (atst/T s/Int) (an/apply-truthy-local u true)))
    (is (= u (an/apply-truthy-local u false))))
  (is (= (atst/T s/Int) (an/apply-truthy-local (at/->MaybeT (atst/T s/Int)) true))))

(deftest partition-values-keywords-test
  (let [u (ato/union-type #{(ato/exact-value-type :a) (ato/exact-value-type :b) (atst/T s/Int)})
        pa (an/partition-type-for-values u [:a] true)
        na (an/partition-type-for-values u [:a] false)]
    (is (= (ato/exact-value-type :a) pa))
    (is (= (ato/union-type #{(ato/exact-value-type :b) (atst/T s/Int)}) na))))
