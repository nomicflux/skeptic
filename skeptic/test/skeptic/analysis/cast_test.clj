(ns skeptic.analysis.cast-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as sut]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.types :as at]))

(deftest directional-cast-kernel-test
  (let [exact (sut/check-cast (ab/schema->type s/Int) (ab/schema->type s/Int))
        target-dyn (sut/check-cast (ab/schema->type s/Int) (ab/schema->type s/Any))
        nested-dyn (sut/check-cast (ab/schema->type {:a s/Any}) (ab/schema->type {:a s/Int}))
        target-union (sut/check-cast (ab/schema->type s/Int)
                                     (ab/schema->type (s/either s/Int s/Str)))
        source-union (sut/check-cast (ab/schema->type (s/either s/Int s/Str))
                                     (ab/schema->type s/Int))
        nilability (sut/check-cast (ab/schema->type (s/maybe s/Any)) (ab/schema->type s/Int))
        domain-failure (sut/check-cast (ab/schema->type (s/=> s/Int s/Int))
                                       (ab/schema->type (s/=> s/Int s/Str)))
        domain-child (first (-> domain-failure :children first :children))
        range-failure (sut/check-cast (ab/schema->type (s/=> s/Str s/Int))
                                      (ab/schema->type (s/=> s/Int s/Int)))
        range-child (last (-> range-failure :children first :children))
        target-intersection (sut/check-cast (ab/schema->type s/Int)
                                            (ab/schema->type (s/both s/Any s/Int)))]
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
        generalized (sut/check-cast (ab/schema->type s/Any) (at/->ForallT 'X x))
        capture (sut/check-cast x (at/->ForallT 'X x))
        instantiated (sut/check-cast (at/->ForallT 'X x) (ab/schema->type s/Any))
        sealed (sut/check-cast x (ab/schema->type s/Any))
        sealed-type (:sealed-type sealed)
        collapsed (sut/check-cast sealed-type x)
        sealed-mismatch (sut/check-cast sealed-type y)
        dyn-mismatch (sut/check-cast (ab/schema->type s/Any) x)
        placeholder-mismatch (sut/check-cast (at/->PlaceholderT ::hole) x)
        abstract-mismatch (sut/check-cast (ab/schema->type s/Int) x)]
    (is (not (:ok? generalized)))
    (is (= :generalize (:rule generalized)))
    (is (= :generalize-failed (:reason generalized)))
    (is (= :type-var-target (-> generalized :children first :rule)))
    (is (= :abstract-target-mismatch (-> generalized :children first :reason)))
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
    (is (not (:ok? dyn-mismatch)))
    (is (= :type-var-target (:rule dyn-mismatch)))
    (is (= :abstract-target-mismatch (:reason dyn-mismatch)))
    (is (not (:ok? placeholder-mismatch)))
    (is (= :type-var-target (:rule placeholder-mismatch)))
    (is (= :abstract-target-mismatch (:reason placeholder-mismatch)))
    (is (not (:ok? abstract-mismatch)))
    (is (= :type-var-target (:rule abstract-mismatch)))
    (is (= :abstract-target-mismatch (:reason abstract-mismatch)))))

(deftest tamper-rules-test
  (let [x (at/->TypeVarT 'X)
        sealed-type (:sealed-type (sut/check-cast x (ab/schema->type s/Any)))
        inspect-result (ascs/check-type-test sealed-type (ab/schema->type s/Int))
        escape-result (ascs/exit-nu-scope sealed-type 'X)
        safe-exit (ascs/exit-nu-scope sealed-type 'Y)
        increment-analogue (sut/check-cast sealed-type (ab/schema->type s/Int))]
    (testing "polymorphic identity analogue seals and collapses safely"
      (is (:ok? (sut/check-cast sealed-type x))))
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

(deftest quantified-boundary-tamper-test
  (let [x (at/->TypeVarT 'X)
        leaky-target (at/->ForallT 'X
                                   (at/->FunT [(at/->FnMethodT [x]
                                                               (ab/schema->type s/Any)
                                                               1
                                                               false)]))
        result (sut/check-cast (ab/schema->type (s/=> s/Any s/Any))
                               leaky-target)]
    (is (not (:ok? result)))
    (is (= :nu-tamper (:rule result)))
    (is (= :nu-tamper (:reason result)))
    (is (= :global (:blame-polarity result)))
    (is (= :function (-> result :children first :rule)))))

(deftest vector-cast-kernel-honors-homogeneous-targets-test
  (let [tuple-any (ab/schema->type [s/Any s/Any s/Any])
        homogeneous-int (ab/schema->type [s/Int])
        triple-int (ab/schema->type [s/Int s/Int s/Int])
        pair-int (ab/schema->type [s/Int s/Int])
        quad-int (ab/schema->type [s/Int s/Int s/Int s/Int])
        homogeneous-cast (sut/check-cast tuple-any homogeneous-int)
        triple-cast (sut/check-cast tuple-any triple-int)
        pair-cast (sut/check-cast tuple-any pair-int)
        quad-cast (sut/check-cast tuple-any quad-int)]
    (is (:ok? homogeneous-cast))
    (is (= :vector (:rule homogeneous-cast)))
    (is (:ok? triple-cast))
    (is (= :vector (:rule triple-cast)))
    (is (not (:ok? pair-cast)))
    (is (= :vector-arity-mismatch (:reason pair-cast)))
    (is (not (:ok? quad-cast)))
    (is (= :vector-arity-mismatch (:reason quad-cast)))
    (is (avc/value-satisfies-type? [1 2 3] homogeneous-int))
    (is (avc/value-satisfies-type? [1 2 3] triple-int))
    (is (not (avc/value-satisfies-type? [1 2 3] pair-int)))
    (is (not (avc/value-satisfies-type? [1 2 3] quad-int)))))

(deftest seq-vector-cast-kernel-test
  (let [seq-int (ab/schema->type (list s/Int))
        vec-int (ab/schema->type [s/Int])
        seq-str (ab/schema->type (list s/Str))
        ok (sut/check-cast seq-int vec-int)
        bad (sut/check-cast seq-str vec-int)
        rev (sut/check-cast vec-int seq-int)]
    (is (:ok? ok))
    (is (= :seq-to-vector (:rule ok)))
    (is (not (:ok? bad)))
    (is (= :seq-to-vector (:rule bad)))
    (is (:ok? rev))
    (is (= :vector-to-seq (:rule rev)))))

(deftest schema-cast-adapter-rejects-semantic-inputs-test
  (let [check-cast (requiring-resolve 'skeptic.analysis.schema.cast/check-cast)]
    (is (thrown-with-msg? IllegalArgumentException
                          #"Expected schema value"
                          (check-cast (at/->GroundT :int 'Int) s/Int)))
    (is (thrown-with-msg? IllegalArgumentException
                          #"Expected schema value"
                          (check-cast s/Int (at/->GroundT :int 'Int))))))

(deftest semantic-map-cast-regressions-test
  (let [schema {:a s/Int :b s/Str s/Keyword s/Any}
        valid {:a 1 :b "hello"}
        valid-with-extra {:a 1 :b "hello" :c 5}
        missing-required {:a 1}
        cast-result (sut/check-cast (ab/schema->type {:a s/Int :b s/Str})
                                    (ab/schema->type schema))]
    (is (nil? (s/check schema valid)))
    (is (nil? (s/check schema valid-with-extra)))
    (is (some? (s/check schema missing-required)))
    (is (avc/value-satisfies-type? valid (ab/schema->type schema)))
    (is (avc/value-satisfies-type? valid-with-extra (ab/schema->type schema)))
    (is (not (avc/value-satisfies-type? missing-required (ab/schema->type schema))))
    (is (:ok? cast-result))
    (is (= :map (:rule cast-result))))
  (let [schema {s/Keyword s/Int}
        empty-value {}
        cast-result (sut/check-cast (ab/schema->type empty-value)
                                    (ab/schema->type schema))]
    (is (nil? (s/check schema empty-value)))
    (is (avc/value-satisfies-type? empty-value (ab/schema->type schema)))
    (is (:ok? cast-result))
    (is (= :map (:rule cast-result)))))

(deftest leaf-overlap-host-number-and-str-arg-test
  (let [int-g (at/->GroundT :int 'Int)
        num-g (at/->GroundT {:class java.lang.Number} 'Number)
        maybe-obj (at/->MaybeT (at/->GroundT {:class java.lang.Object} 'Object))]
    (is (:ok? (sut/check-cast int-g num-g)))
    (is (not (:ok? (sut/check-cast num-g int-g))))
    (is (:ok? (sut/check-cast int-g maybe-obj)))
    (is (not (:ok? (sut/check-cast maybe-obj int-g))))))
