(ns skeptic.analysis.cast.quantified-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as sut]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.types :as at]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest quantified-generalize-instantiate-and-seals-test
  (let [x (at/->TypeVarT 'X)
        y (at/->TypeVarT 'Y)
        generalized (sut/check-cast (T s/Any) (at/->ForallT 'X x))
        capture (sut/check-cast x (at/->ForallT 'X x))
        instantiated (sut/check-cast (at/->ForallT 'X x) (T s/Any))
        sealed (sut/check-cast x (T s/Any))
        sealed-type (:sealed-type sealed)
        collapsed (sut/check-cast sealed-type x)
        mismatch (sut/check-cast sealed-type y)]
    (is (= :generalize (:rule generalized)))
    (is (= :generalize-failed (:reason generalized)))
    (is (= :forall-capture (:reason capture)))
    (is (= :instantiate (:rule instantiated)))
    (is (:ok? sealed))
    (is (at/sealed-dyn-type? sealed-type))
    (is (= :seal (:rule sealed)))
    (is (= :sealed-collapse (:rule collapsed)))
    (is (= :sealed-ground-mismatch (:reason mismatch)))))

(deftest abstract-type-mismatch-rules-test
  (let [x (at/->TypeVarT 'X)
        dyn-mismatch (sut/check-cast (T s/Any) x)
        placeholder-mismatch (sut/check-cast (at/->PlaceholderT ::hole) x)
        concrete-mismatch (sut/check-cast (T s/Int) x)
        source-mismatch (sut/check-cast x (T s/Int))]
    (is (= :abstract-target-mismatch (:reason dyn-mismatch)))
    (is (= :abstract-target-mismatch (:reason placeholder-mismatch)))
    (is (= :abstract-target-mismatch (:reason concrete-mismatch)))
    (is (= :abstract-source-mismatch (:reason source-mismatch)))))

(deftest tamper-and-scope-exit-rules-test
  (let [x (at/->TypeVarT 'X)
        sealed-type (:sealed-type (sut/check-cast x (T s/Any)))
        inspect-result (ascs/check-type-test sealed-type (T s/Int))
        escape-result (ascs/exit-nu-scope sealed-type 'X)
        safe-exit (ascs/exit-nu-scope sealed-type 'Y)
        leaky-target (at/->ForallT 'X
                                   (at/->FunT [(at/->FnMethodT [x]
                                                               (T s/Any)
                                                               1
                                                               false
                                                               '[x])]))
        leaky-result (sut/check-cast (T (s/=> s/Any s/Any)) leaky-target)]
    (testing "inspecting a sealed value is global tamper"
      (is (= :is-tamper (:rule inspect-result)))
      (is (= :global (:blame-polarity inspect-result))))
    (testing "moving a sealed value out of scope is global tamper"
      (is (= :nu-tamper (:rule escape-result)))
      (is (= :global (:blame-polarity escape-result)))
      (is (= :nu-pass (:rule safe-exit))))
    (testing "quantified exit runs the nu-scope check"
      (is (= :nu-tamper (:rule leaky-result)))
      (is (= :nu-tamper (:reason leaky-result))))))
