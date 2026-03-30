(ns skeptic.analysis-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.analyzer.ast :as ana.ast]
            [schema.core :as s]
            [skeptic.analysis :as analysis]
            [skeptic.analysis.schema :as as]))

(deftest tagged-polymorphic-type-helpers-test
  (let [type-var (as/->TypeVarT 'X)
        forall (as/->ForallT 'X (as/->FunT [(as/->FnMethodT [type-var]
                                                           type-var
                                                           1
                                                           false)]))
        sealed (as/->SealedDynT type-var)
        localized (as/localize-schema-value {:poly forall
                                             :sealed sealed})
        stripped (as/strip-derived-types {:schema s/Int
                                          :type forall
                                          :output-type sealed})]
    (is (as/type-var-type? type-var))
    (is (as/forall-type? forall))
    (is (as/sealed-dyn-type? sealed))
    (is (as/semantic-type-value? forall))
    (is (= forall (get localized :poly)))
    (is (= sealed (get localized :sealed)))
    (is (= {:schema s/Int} stripped))
    (is (= #{'Y} (as/type-free-vars (as/->ForallT 'X (as/->FunT [(as/->FnMethodT [type-var]
                                                                                (as/->TypeVarT 'Y)
                                                                                1
                                                                                false)])))))
    (is (= (as/->ForallT 'X (as/->TypeVarT 'X))
           (as/type-substitute (as/->ForallT 'X (as/->TypeVarT 'X))
                               'X
                               (as/schema->type s/Any))))))

(deftest quantified-cast-kernel-test
  (let [x (as/->TypeVarT 'X)
        y (as/->TypeVarT 'Y)
        generalized (as/check-cast s/Any (as/->ForallT 'X x))
        capture (as/check-cast x (as/->ForallT 'X x))
        instantiated (as/check-cast (as/->ForallT 'X x) s/Any)
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
    (is (as/sealed-dyn-type? sealed-type))

    (is (:ok? collapsed))
    (is (= :sealed-collapse (:rule collapsed)))

    (is (not (:ok? sealed-mismatch)))
    (is (= :sealed-collapse (:rule sealed-mismatch)))
    (is (= :sealed-ground-mismatch (:reason sealed-mismatch)))

    (is (not (:ok? abstract-mismatch)))
    (is (= :type-var-target (:rule abstract-mismatch)))
    (is (= :abstract-target-mismatch (:reason abstract-mismatch)))))

(deftest tamper-rules-test
  (let [x (as/->TypeVarT 'X)
        sealed-type (:sealed-type (as/check-cast x s/Any))
        inspect-result (as/check-type-test sealed-type s/Int)
        escape-result (as/exit-nu-scope sealed-type 'X)
        safe-exit (as/exit-nu-scope sealed-type 'Y)
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

(deftest ordinary-analysis-remains-first-order-test
  (let [ast (analysis/attach-schema-info-loop {}
                                              '(let [id (fn [x] x)]
                                                 (id 1))
                                              {:ns 'skeptic.analysis-schema-test})
        node-schemas (keep :schema (ana.ast/nodes ast))]
    (is (seq node-schemas))
    (is (not-any? (comp as/forall-type? as/schema->type) node-schemas))
    (is (not-any? (comp as/type-var-type? as/schema->type) node-schemas))
    (is (not-any? (comp as/sealed-dyn-type? as/schema->type) node-schemas))))
