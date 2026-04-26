(ns skeptic.analysis.sum-types-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.sum-types :as sut]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.test-helpers :refer [tp]]))

(deftest boolean-ground-is-closed-sum
  (let [bool (atst/T s/Bool)]
    (is (sut/sum-type? bool))
    (is (sut/exhausted-by-values? bool [true false]))
    (is (not (sut/exhausted-by-values? bool [true])))))

(deftest value-union-exhaustion
  (let [enum (atst/T (s/enum :a :b))]
    (is (sut/sum-type? enum))
    (is (sut/exhausted-by-values? enum [:a :b]))
    (is (not (sut/exhausted-by-values? enum [:a])))))

(deftest closed-union-type-exhaustion
  (let [union (ato/union-type tp [(atst/T s/Str) (atst/T s/Int)])]
    (is (sut/exhausted-by-types? union [(atst/T s/Str) (atst/T s/Int)]))
    (is (not (sut/exhausted-by-types? union [(atst/T s/Str)])))))

(deftest unknown-union-is-open
  (let [union (ato/union-type tp [(atst/T s/Str) (at/Dyn tp)])]
    (is (not (sut/sum-type? union)))
    (is (not (sut/exhausted-by-types? union [(atst/T s/Str) (at/Dyn tp)])))))

(defn- atom* [expr polarity] {:kind :atom :expr expr :polarity polarity})
(defn- and* [& parts] {:kind :conjunction :parts (vec parts)})
(defn- or* [& parts] {:kind :disjunction :parts (vec parts)})

(deftest formulas-cover-bool-tautology
  (is (sut/formulas-cover? [(atom* 'P true) (atom* 'P false)]))
  (is (not (sut/formulas-cover? [(atom* 'P true)]))))

(deftest formulas-cover-pair-product
  (is (sut/formulas-cover?
       [(and* (atom* 'P true)  (atom* 'Q true))
        (and* (atom* 'P true)  (atom* 'Q false))
        (and* (atom* 'P false) (atom* 'Q true))
        (and* (atom* 'P false) (atom* 'Q false))])))

(deftest formulas-cover-pair-three-of-four
  (is (not (sut/formulas-cover?
            [(and* (atom* 'P true)  (atom* 'Q true))
             (and* (atom* 'P true)  (atom* 'Q false))
             (and* (atom* 'P false) (atom* 'Q true))]))))

(deftest formulas-cover-disjunction-of-negations
  (is (sut/formulas-cover?
       [(and* (atom* 'P true) (atom* 'Q true))
        (or*  (atom* 'P false) (atom* 'Q false))])))

(deftest formulas-cover-cap
  (let [atoms (mapv #(atom* (symbol (str "A" %)) true) (range 13))]
    (is (not (sut/formulas-cover? [{:kind :conjunction :parts atoms}])))))

(deftest sum-alternatives-on-conditional-test
  (let [k1 (ato/exact-value-type tp :a)
        k2 (ato/exact-value-type tp :b)
        cond-type (at/->ConditionalT tp [[:keyword? k1 nil] [:keyword? k2 nil]])
        alts (sut/sum-alternatives cond-type)]
    (is (= 2 (count alts)))
    (is (some #(at/type=? k1 %) alts))
    (is (some #(at/type=? k2 %) alts))
    (is (sut/exhausted-by-values? cond-type [:a :b]))
    (is (not (sut/exhausted-by-values? cond-type [:a])))))
