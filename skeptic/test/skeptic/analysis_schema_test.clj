(ns skeptic.analysis-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.analyzer.ast :as ana.ast]
            [schema.core :as s]
            [skeptic.analysis :as analysis]
            [skeptic.analysis.schema :as as]))

(declare UnboundSchemaRef)

(def BoundSchemaRef s/Int)

(def RecursiveSchemaRef [#'RecursiveSchemaRef])

(defn T
  [schema]
  (as/schema->type schema))

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
    (is (= {:schema s/Int
            :type forall
            :output-type sealed}
           stripped))
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
        node-types (keep :type (ana.ast/nodes ast))]
    (is (seq node-types))
    (is (not-any? as/forall-type? node-types))
    (is (not-any? as/type-var-type? node-types))
    (is (not-any? as/sealed-dyn-type? node-types))))

(deftest display-keeps-type-and-schema-domains-separate-test
  (let [type-var (as/->TypeVarT 'X)
        polymorphic-map (as/->MapT {(as/->GroundT :keyword 'Keyword)
                                    (as/->ForallT 'X (as/->FunT [(as/->FnMethodT [type-var]
                                                                               (as/->SealedDynT type-var)
                                                                               1
                                                                               false)]))})]
    (is (= '{Keyword (forall X (=> (sealed X) X))}
           (as/display-form polymorphic-map)))
    (is (= "hello"
           (as/display-form (T (s/eq "hello")))))
    (is (= 'Int
           (as/display-form s/Int)))))

(deftest raw-schema-var-normalization-test
  (testing "bound vars are dereferenced during schema localization"
    (is (= s/Int
           (as/canonicalize-schema #'BoundSchemaRef)))
    (is (= "Int"
           (as/render-type (as/schema->type #'BoundSchemaRef)))))

  (testing "unbound vars become placeholders instead of leaking into schema conversion"
    (is (= (as/placeholder-schema 'skeptic.analysis-schema-test/UnboundSchemaRef)
           (as/canonicalize-schema #'UnboundSchemaRef)))
    (is (= 'skeptic.analysis-schema-test/UnboundSchemaRef
           (-> #'UnboundSchemaRef
               as/schema->type
               :ref)))
    (let [unbound-root (.getRawRoot ^clojure.lang.Var #'UnboundSchemaRef)]
      (is (= (as/placeholder-schema 'skeptic.analysis-schema-test/UnboundSchemaRef)
             (as/canonicalize-schema unbound-root)))
      (is (= 'skeptic.analysis-schema-test/UnboundSchemaRef
             (-> unbound-root
                 as/schema->type
                 :ref)))))

  (testing "recursive vars fall back to placeholders instead of recursing forever"
    (is (= [(as/placeholder-schema 'skeptic.analysis-schema-test/RecursiveSchemaRef)]
           (as/canonicalize-schema #'RecursiveSchemaRef)))
    (let [recursive-type (as/schema->type #'RecursiveSchemaRef)]
      (is (as/vector-type? recursive-type))
      (is (= 'skeptic.analysis-schema-test/RecursiveSchemaRef
             (-> recursive-type :items first :ref))))))

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

    (is (as/value-satisfies-type? [1 2 3] homogeneous-int))
    (is (as/value-satisfies-type? [1 2 3] triple-int))
    (is (not (as/value-satisfies-type? [1 2 3] pair-int)))
    (is (not (as/value-satisfies-type? [1 2 3] quad-int)))))

(deftest non-literal-get-uses-key-domain-regression-test
  (let [schema {:a s/Int
                s/Keyword s/Str}
        literal-result (as/map-get-schema schema
                                          (as/exact-key-query s/Keyword :a :a))
        domain-result (as/map-get-schema schema
                                         (as/domain-key-query s/Keyword 'k))]
    (is (= s/Int literal-result))
    (is (as/schema-equivalent? (as/join s/Int s/Str)
                               domain-result))))
