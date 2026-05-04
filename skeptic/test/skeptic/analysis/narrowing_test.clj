(ns skeptic.analysis.narrowing-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.test-helpers :refer [is-type= T tp]]
            [schema.core :as s]))

(deftest classify-leaf-test
  (testing "ground types"
    (is (= :matches (an/classify-leaf-for-predicate? {:pred :string?} (T s/Str))))
    (is (= :does-not-match (an/classify-leaf-for-predicate? {:pred :string?} (T s/Int)))))
  (testing "fn?"
    (is (= :matches (an/classify-leaf-for-predicate? {:pred :fn?} (at/->FunT tp []))))))

(deftest partition-string-predicate-union-test
  (let [u (ato/union-type tp #{(T s/Str) (T s/Int)})
        pos (an/partition-type-for-predicate u {:pred :string?} true)
        neg (an/partition-type-for-predicate u {:pred :string?} false)]
    (is-type= (T s/Str) pos)
    (is-type= (T s/Int) neg)))

(deftest partition-maybe-nil-some-test
  (let [m (at/->MaybeT tp (T s/Int))
        npos (an/partition-type-for-predicate m {:pred :nil?} true)
        nneg (an/partition-type-for-predicate m {:pred :nil?} false)
        spos (an/partition-type-for-predicate m {:pred :some?} true)
        sneg (an/partition-type-for-predicate m {:pred :some?} false)]
    (is (at/value-type? npos))
    (is (nil? (:value npos)))
    (is-type= (T s/Int) nneg)
    (is-type= (T s/Int) spos)
    (is (at/value-type? sneg))
    (is (nil? (:value sneg)))))

(deftest partition-dyn-narrows-on-classifying-positive-test
  (testing "positive ground-classifying predicate narrows Dyn to the ground"
    (is-type= (T s/Str)
              (an/partition-type-for-predicate (at/Dyn tp) {:pred :string?} true))
    (is-type= (T s/Keyword)
              (an/partition-type-for-predicate (at/Dyn tp) {:pred :keyword?} true)))
  (testing "negative polarity leaves Dyn unchanged (no complement type)"
    (is-type= (at/Dyn tp)
              (an/partition-type-for-predicate (at/Dyn tp) {:pred :string?} false)))
  (testing "non-classifying predicate leaves Dyn unchanged on either polarity"
    (is-type= (at/Dyn tp)
              (an/partition-type-for-predicate (at/Dyn tp) {:pred :fn?} true))
    (is-type= (at/Dyn tp)
              (an/partition-type-for-predicate (at/Dyn tp) {:pred :fn?} false)))
  (testing "negative :some? on Dyn narrows to (eq nil)"
    (let [r (an/partition-type-for-predicate (at/Dyn tp) {:pred :some?} false)]
      (is (at/value-type? r))
      (is (nil? (:value r)))))
  (testing "positive :some? on Dyn leaves Dyn unchanged (no useful refinement)"
    (is-type= (at/Dyn tp)
              (an/partition-type-for-predicate (at/Dyn tp) {:pred :some?} true))))

(deftest apply-truthy-local-test
  (let [u (ato/union-type tp #{(at/->ValueT tp (at/->GroundT tp :bool 'Bool) false)
                               (T s/Int)})]
    (is-type= (T s/Int) (an/apply-truthy-local u true))
    (is (= u (an/apply-truthy-local u false))))
  (is-type= (T s/Int) (an/apply-truthy-local (at/->MaybeT tp (T s/Int)) true)))

(deftest partition-values-keywords-test
  (let [u (ato/union-type tp #{(ato/exact-value-type tp :a) (ato/exact-value-type tp :b) (T s/Int)})
        pa (an/partition-type-for-values u [:a] true)
        na (an/partition-type-for-values u [:a] false)]
    (is-type= (ato/exact-value-type tp :a) pa)
    (is (= (ato/union-type tp #{(ato/exact-value-type tp :b) (T s/Int)}) na))))

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

(deftest partition-conditional-by-predicate-test
  (let [int-t (T s/Int)
        str-t (T s/Str)
        cond-type (at/->ConditionalT tp [[:integer? int-t nil] [:string? str-t nil]])
        kept (an/partition-type-for-predicate cond-type {:pred :integer?} true)
        dropped (an/partition-type-for-predicate cond-type {:pred :integer?} false)]
    (is-type= int-t kept)
    (is-type= str-t dropped)))

(deftest partition-conditional-by-values-test
  (let [int-t (T s/Int)
        true-v (ato/exact-value-type tp true)
        cond-type (at/->ConditionalT tp [[:integer? int-t nil] [:boolean? true-v nil]])
        kept-true (an/partition-type-for-values cond-type [true] true)]
    (is-type= true-v kept-true)))

(deftest can-be-falsy-conditional-test
  (let [int-t (T s/Int)
        false-v (ato/exact-value-type tp false)
        all-truthy (at/->ConditionalT tp [[:integer? int-t nil]])
        has-falsy (at/->ConditionalT tp [[:integer? int-t nil] [:boolean? false-v nil]])]
    (is (not (an/can-be-falsy-type? all-truthy)))
    (is (an/can-be-falsy-type? has-falsy))))

(deftest apply-truthy-local-conditional-test
  (let [int-t (T s/Int)
        false-v (ato/exact-value-type tp false)
        cond-type (at/->ConditionalT tp [[:integer? int-t nil] [:boolean? false-v nil]])
        result (an/apply-truthy-local cond-type true)]
    (is-type= int-t result)))
