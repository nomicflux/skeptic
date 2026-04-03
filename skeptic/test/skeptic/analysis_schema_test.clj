(ns skeptic.analysis-schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.analyzer.ast :as ana.ast]
            [schema.core :as s]
            [skeptic.analysis :as analysis]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.algebra :as aba]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema :as as]
            [skeptic.analysis.schema.cast-support :as ascs]
            [skeptic.analysis.schema.map-ops :as asm]
            [skeptic.analysis.schema.value-check :as asv]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(declare UnboundSchemaRef)

(def BoundSchemaRef s/Int)

(def RecursiveSchemaRef [#'RecursiveSchemaRef])

(defn T
  [schema]
  (ab/schema->type schema))

(deftest tagged-polymorphic-type-helpers-test
  (let [type-var (at/->TypeVarT 'X)
        forall (at/->ForallT 'X (at/->FunT [(at/->FnMethodT [type-var]
                                                           type-var
                                                           1
                                                           false)]))
        sealed (at/->SealedDynT type-var)
        localized (abl/localize-schema-value {:poly forall
                                             :sealed sealed})
        stripped (abr/strip-derived-types {:schema s/Int
                                          :type forall
                                          :output-type sealed})]
    (is (at/type-var-type? type-var))
    (is (at/forall-type? forall))
    (is (at/sealed-dyn-type? sealed))
    (is (at/semantic-type-value? forall))
    (is (= forall (get localized :poly)))
    (is (= sealed (get localized :sealed)))
    (is (= {:schema s/Int
            :type forall
            :output-type sealed}
           stripped))
    (is (= #{'Y} (aba/type-free-vars (at/->ForallT 'X (at/->FunT [(at/->FnMethodT [type-var]
                                                                                (at/->TypeVarT 'Y)
                                                                                1
                                                                                false)])))))
    (is (= (at/->ForallT 'X (at/->TypeVarT 'X))
           (aba/type-substitute (at/->ForallT 'X (at/->TypeVarT 'X))
                               'X
                               (ab/schema->type s/Any))))))

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

(deftest ordinary-analysis-remains-first-order-test
  (let [ast (analysis/attach-schema-info-loop {}
                                              '(let [id (fn [x] x)]
                                                 (id 1))
                                              {:ns 'skeptic.analysis-schema-test})
        node-types (keep :type (ana.ast/nodes ast))]
    (is (seq node-types))
    (is (not-any? at/forall-type? node-types))
    (is (not-any? at/type-var-type? node-types))
    (is (not-any? at/sealed-dyn-type? node-types))))

(deftest display-keeps-type-and-schema-domains-separate-test
  (let [type-var (at/->TypeVarT 'X)
        polymorphic-map (at/->MapT {(at/->GroundT :keyword 'Keyword)
                                    (at/->ForallT 'X (at/->FunT [(at/->FnMethodT [type-var]
                                                                               (at/->SealedDynT type-var)
                                                                               1
                                                                               false)]))})]
    (is (= '{Keyword (forall X (=> (sealed X) X))}
           (abr/display-form polymorphic-map)))
    (is (= "hello"
           (abr/display-form (T (s/eq "hello")))))
    (is (= 'Int
           (abr/display-form s/Int)))))

(deftest raw-schema-var-normalization-test
  (testing "bound vars are dereferenced during schema localization"
    (is (= s/Int
           (abc/canonicalize-schema #'BoundSchemaRef)))
    (is (= "Int"
           (abr/render-type (ab/schema->type #'BoundSchemaRef)))))

  (testing "unbound vars become placeholders instead of leaking into schema conversion"
    (is (= (sb/placeholder-schema 'skeptic.analysis-schema-test/UnboundSchemaRef)
           (abc/canonicalize-schema #'UnboundSchemaRef)))
    (is (= 'skeptic.analysis-schema-test/UnboundSchemaRef
           (-> #'UnboundSchemaRef
               ab/schema->type
               :ref)))
    (let [unbound-root (.getRawRoot ^clojure.lang.Var #'UnboundSchemaRef)]
      (is (= (sb/placeholder-schema 'skeptic.analysis-schema-test/UnboundSchemaRef)
             (abc/canonicalize-schema unbound-root)))
      (is (= 'skeptic.analysis-schema-test/UnboundSchemaRef
             (-> unbound-root
                 ab/schema->type
                 :ref)))))

  (testing "recursive vars fall back to placeholders instead of recursing forever"
    (is (= [(sb/placeholder-schema 'skeptic.analysis-schema-test/RecursiveSchemaRef)]
           (abc/canonicalize-schema #'RecursiveSchemaRef)))
    (let [recursive-type (ab/schema->type #'RecursiveSchemaRef)]
      (is (at/vector-type? recursive-type))
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

    (is (asv/value-satisfies-type? [1 2 3] homogeneous-int))
    (is (asv/value-satisfies-type? [1 2 3] triple-int))
    (is (not (asv/value-satisfies-type? [1 2 3] pair-int)))
    (is (not (asv/value-satisfies-type? [1 2 3] quad-int)))))

(deftest non-literal-get-uses-key-domain-regression-test
  (let [schema {:a s/Int
                s/Keyword s/Str}
        literal-result (asm/map-get-schema schema
                                           (asm/exact-key-query s/Keyword :a :a))
        domain-result (asm/map-get-schema schema
                                          (asm/domain-key-query s/Keyword 'k))]
    (is (= s/Int literal-result))
    (is (ascs/schema-equivalent? (sb/join s/Int s/Str)
                               domain-result))))
