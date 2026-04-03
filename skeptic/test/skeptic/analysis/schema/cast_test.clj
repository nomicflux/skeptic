 (ns skeptic.analysis.schema.cast-test
   (:require [clojure.test :refer [deftest is testing]]
             [schema.core :as s]
             [skeptic.analysis.schema :as as]
             [skeptic.analysis.schema.cast-support :as ascs]
             [skeptic.analysis.schema.value-check :as asv]
             [skeptic.analysis.types :as at]))

 (deftest directional-cast-kernel-test
   (let [exact (as/check-cast s/Int s/Int)
         target-dyn (as/check-cast s/Int s/Any)
         nested-dyn (as/check-cast {:a s/Any} {:a s/Int})
         target-union (as/check-cast s/Int (s/either s/Int s/Str))
         source-union (as/check-cast (s/either s/Int s/Str) s/Int)
         nilability (as/check-cast (s/maybe s/Any) s/Int)
         domain-failure (as/check-cast (s/=> s/Int s/Int)
                                       (s/=> s/Int s/Str))
         domain-child (first (-> domain-failure :children first :children))
         range-failure (as/check-cast (s/=> s/Str s/Int)
                                      (s/=> s/Int s/Int))
         range-child (last (-> range-failure :children first :children))
         target-intersection (as/check-cast s/Int (s/both s/Any s/Int))]
     (is (:ok? exact))
     (is (= :exact (:rule exact)))
     (is (:ok? target-dyn))
     (is (= :target-dyn (:rule target-dyn)))
     (is (:ok? nested-dyn))
     (is (= :map (:rule nested-dyn)))
     (is (= :residual-dynamic (-> nested-dyn :children first :rule)))
     (is (:ok? target-union))
     (is (= :target-union (:rule target-union)))
     (is (not (:ok? source-union)))
     (is (= :source-union (:rule source-union)))
     (is (not (:ok? nilability)))
     (is (= :maybe-source (:rule nilability)))
     (is (not (:ok? domain-failure)))
     (is (= :negative (:blame-polarity domain-child)))
     (is (not (:ok? range-failure)))
     (is (= :positive (:blame-polarity range-child)))
     (is (:ok? target-intersection))
     (is (= :target-intersection (:rule target-intersection)))))

 (deftest quantified-cast-kernel-test
   (let [x (at/->TypeVarT 'X)
         y (at/->TypeVarT 'Y)
         generalized (as/check-cast s/Any (at/->ForallT 'X x))
         capture (as/check-cast x (at/->ForallT 'X x))
         instantiated (as/check-cast (at/->ForallT 'X x) s/Any)
         sealed (as/check-cast x s/Any)
         sealed-type (:sealed-type sealed)
         collapsed (as/check-cast sealed-type x)
         sealed-mismatch (as/check-cast sealed-type y)
         abstract-mismatch (as/check-cast s/Int x)]
     (is (:ok? generalized))
     (is (= :generalize (:rule generalized)))
     (is (= :type-var-target (-> generalized :children first :rule)))
     (is (not (:ok? capture)))
     (is (= :generalize (:rule capture)))
     (is (= :forall-capture (:reason capture)))
     (is (:ok? instantiated))
     (is (= :instantiate (:rule instantiated)))
     (is (= :exact (-> instantiated :children first :rule)))
     (is (:ok? sealed))
     (is (= :seal (:rule sealed)))
     (is (at/sealed-dyn-type? sealed-type))
     (is (:ok? collapsed))
     (is (= :sealed-collapse (:rule collapsed)))
     (is (not (:ok? sealed-mismatch)))
     (is (= :sealed-collapse (:rule sealed-mismatch)))
     (is (= :sealed-ground-mismatch (:reason sealed-mismatch)))
     (is (not (:ok? abstract-mismatch)))
     (is (= :type-var-target (:rule abstract-mismatch)))
     (is (= :abstract-target-mismatch (:reason abstract-mismatch)))))

 (deftest tamper-rules-test
   (let [x (at/->TypeVarT 'X)
         sealed-type (:sealed-type (as/check-cast x s/Any))
         inspect-result (ascs/check-type-test sealed-type s/Int)
         escape-result (ascs/exit-nu-scope sealed-type 'X)
         safe-exit (ascs/exit-nu-scope sealed-type 'Y)
         increment-analogue (as/check-cast sealed-type s/Int)]
     (testing "polymorphic identity analogue seals and collapses safely"
       (is (:ok? (as/check-cast sealed-type x))))
     (testing "non-parametric increment analogue fails on sealed integer use"
       (is (not (:ok? increment-analogue)))
       (is (= :sealed-conflict (:rule increment-analogue))))
     (testing "sealed inspection is tampering"
       (is (not (:ok? inspect-result)))
       (is (= :is-tamper (:rule inspect-result)))
       (is (= :global (:blame-polarity inspect-result))))
     (testing "sealed escape is tampering"
       (is (not (:ok? escape-result)))
       (is (= :nu-tamper (:rule escape-result)))
       (is (= :global (:blame-polarity escape-result)))
       (is (:ok? safe-exit))
       (is (= :nu-pass (:rule safe-exit))))))

 (deftest vector-cast-kernel-honors-homogeneous-targets-test
   (let [tuple-any [s/Any s/Any s/Any]
         homogeneous-int [s/Int]
         triple-int [s/Int s/Int s/Int]
         pair-int [s/Int s/Int]
         quad-int [s/Int s/Int s/Int s/Int]
         homogeneous-cast (as/check-cast tuple-any homogeneous-int)
         triple-cast (as/check-cast tuple-any triple-int)
         pair-cast (as/check-cast tuple-any pair-int)
         quad-cast (as/check-cast tuple-any quad-int)]
     (is (:ok? homogeneous-cast))
     (is (= :vector (:rule homogeneous-cast)))
     (is (:ok? triple-cast))
     (is (= :vector (:rule triple-cast)))
     (is (not (:ok? pair-cast)))
     (is (= :vector-arity-mismatch (:reason pair-cast)))
     (is (not (:ok? quad-cast)))
     (is (= :vector-arity-mismatch (:reason quad-cast)))
     (is (asv/value-satisfies-type? [1 2 3] homogeneous-int))
     (is (asv/value-satisfies-type? [1 2 3] triple-int))
     (is (not (asv/value-satisfies-type? [1 2 3] pair-int)))
     (is (not (asv/value-satisfies-type? [1 2 3] quad-int)))))
