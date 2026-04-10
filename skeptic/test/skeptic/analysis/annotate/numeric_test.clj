(ns skeptic.analysis.annotate.numeric-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.native-fns :as anf]
            [skeptic.analysis.types :as at])
  (:import [clojure.lang Numbers]))

(def int-ground
  (at/->GroundT :int 'Int))

(def pos-int-refn
  (at/->RefinementT int-ground 'PosInt (constantly true)
                    {:adapter :schema
                     :kind :constrained}))

(deftest integral-ground-type?-test
  (is (aan/integral-ground-type? int-ground))
  (is (aan/integral-ground-type? (at/->ValueT at/Dyn 42)))
  (is (not (aan/integral-ground-type? (at/->GroundT :str 'Str))))
  (is (aan/integral-ground-type? pos-int-refn))
  (is (aan/integral-ground-type?
       (at/->RefinementT (at/->GroundT {:class Long} 'Long) 'X (constantly true) {})))
  (is (not (aan/integral-ground-type?
            (at/->RefinementT (at/->GroundT :str 'Str) 'X (constantly true) {}))))
  (is (aan/integral-ground-type?
       (at/->IntersectionT #{int-ground pos-int-refn})))
  (is (not (aan/integral-ground-type?
            (at/->IntersectionT #{int-ground (at/->GroundT :str 'Str)})))))

(deftest invoke-integral-math-narrow-type-test
  (let [inc-fn {:op :var :form 'inc}
        int-t int-ground]
    (testing "inc of non-const integral local narrows to Int"
      (is (= int-ground
             (aan/invoke-integral-math-narrow-type
              inc-fn
              [{:op :local :form 'n}]
              [int-t]))))
    (testing "inc of refined integral local narrows to Int"
      (is (= int-ground
             (aan/invoke-integral-math-narrow-type
              inc-fn
              [{:op :local :form 'n}]
              [pos-int-refn]))))
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

(deftest narrow-static-numbers-output-test
  (let [native-info (anf/static-call-native-info Numbers 'dec 1)]
    (is (= int-ground
           (aan/narrow-static-numbers-output
            {:method 'dec}
            [{:op :local :form 'n}]
            [pos-int-refn]
            native-info)))))
