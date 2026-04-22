(ns skeptic.analysis.narrowing-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis-test :as atst]
            [skeptic.provenance :as prov]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [schema.core :as s]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest classify-leaf-test
  (testing "ground types"
    (is (= :matches (an/classify-leaf-for-predicate? {:pred :string?} (atst/T s/Str))))
    (is (= :does-not-match (an/classify-leaf-for-predicate? {:pred :string?} (atst/T s/Int)))))
  (testing "fn?"
    (is (= :matches (an/classify-leaf-for-predicate? {:pred :fn?} (at/->FunT tp []))))))

(deftest partition-string-predicate-union-test
  (let [u (ato/union-type tp #{(atst/T s/Str) (atst/T s/Int)})
        pos (an/partition-type-for-predicate u {:pred :string?} true)
        neg (an/partition-type-for-predicate u {:pred :string?} false)]
    (is (at/type=? (atst/T s/Str) pos))
    (is (at/type=? (atst/T s/Int) neg))))

(deftest partition-maybe-nil-some-test
  (let [m (at/->MaybeT tp (atst/T s/Int))
        npos (an/partition-type-for-predicate m {:pred :nil?} true)
        nneg (an/partition-type-for-predicate m {:pred :nil?} false)
        spos (an/partition-type-for-predicate m {:pred :some?} true)
        sneg (an/partition-type-for-predicate m {:pred :some?} false)]
    (is (at/value-type? npos))
    (is (nil? (:value npos)))
    (is (at/type=? (atst/T s/Int) nneg))
    (is (at/type=? (atst/T s/Int) spos))
    (is (at/value-type? sneg))
    (is (nil? (:value sneg)))))

(deftest partition-dyn-unchanged-test
  (is (at/type=? (at/Dyn tp) (an/partition-type-for-predicate (at/Dyn tp) {:pred :string?} true))))

(deftest apply-truthy-local-test
  (let [u (ato/union-type tp #{(at/->ValueT tp (at/->GroundT tp :bool 'Bool) false)
                            (atst/T s/Int)})]
    (is (at/type=? (atst/T s/Int) (an/apply-truthy-local u true)))
    (is (= u (an/apply-truthy-local u false))))
  (is (at/type=? (atst/T s/Int) (an/apply-truthy-local (at/->MaybeT tp (atst/T s/Int)) true))))

(deftest partition-values-keywords-test
  (let [u (ato/union-type tp #{(ato/exact-value-type tp :a) (ato/exact-value-type tp :b) (atst/T s/Int)})
        pa (an/partition-type-for-values u [:a] true)
        na (an/partition-type-for-values u [:a] false)]
    (is (at/type=? (ato/exact-value-type tp :a) pa))
    (is (= (ato/union-type tp #{(ato/exact-value-type tp :b) (atst/T s/Int)}) na))))

(deftest numeric-dyn-predicate-classification-test
  (is (= :matches (an/classify-leaf-for-predicate? {:pred :number?} (at/NumericDyn tp))))
  (is (= :unknown (an/classify-leaf-for-predicate? {:pred :integer?} (at/NumericDyn tp))))
  (is (= :matches
         (an/classify-leaf-for-predicate? {:pred :instance?
                                           :class java.lang.Number}
                                          (at/NumericDyn tp))))
  (is (= :does-not-match
         (an/classify-leaf-for-predicate? {:pred :integer?}
                                          (at/->GroundT tp {:class java.lang.Double} 'Double)))))
