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
