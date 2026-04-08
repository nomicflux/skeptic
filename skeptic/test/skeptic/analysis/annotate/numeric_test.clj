(ns skeptic.analysis.annotate.numeric-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.types :as at]))

(deftest integral-ground-type?-test
  (is (aan/integral-ground-type? (at/->GroundT :int 'Int)))
  (is (aan/integral-ground-type? (at/->ValueT at/Dyn 42)))
  (is (not (aan/integral-ground-type? (at/->GroundT :str 'Str)))))

(deftest invoke-integral-math-narrow-type-test
  (let [inc-fn {:op :var :form 'inc}
        int-t (at/->GroundT :int 'Int)]
    (testing "inc of non-const integral local narrows to Int"
      (is (= (at/->GroundT :int 'Int)
             (aan/invoke-integral-math-narrow-type
              inc-fn
              [{:op :local :form 'n}]
              [int-t]))))
    (testing "const argument — no narrowing"
      (is (nil? (aan/invoke-integral-math-narrow-type
                 inc-fn
                 [{:op :const :val 1}]
                 [(at/->ValueT at/Dyn 1)]))))
    (testing "non-integral arg"
      (is (nil? (aan/invoke-integral-math-narrow-type
                 inc-fn
                 [{:op :local :form 's}]
                 [(at/->GroundT :str 'Str)]))))))
