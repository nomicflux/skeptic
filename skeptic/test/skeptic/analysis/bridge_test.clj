(ns skeptic.analysis.bridge-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.algebra :as aba]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.test-helpers :refer [T tp]]))

(declare UnboundSchemaRef
         DirectRecursiveSchemaRef
         JoinedRecursiveSchemaRef
         RecursiveSeqRef
         RecursiveSetRef)

(def BoundSchemaRef s/Int)
(def RecursiveSchemaRef [#'RecursiveSchemaRef])
(def DirectRecursiveSchemaRef #'DirectRecursiveSchemaRef)
(def JoinedRecursiveSchemaRef [s/Int #'JoinedRecursiveSchemaRef s/Str])
(def RecursiveSeqRef (list s/Int #'RecursiveSeqRef s/Str))
(def RecursiveSetRef #{s/Int #'RecursiveSetRef s/Str})

(deftest resolve-placeholders-stays-bridge-only-test
  (let [placeholder (sb/placeholder-schema 'example/X)
        schema (s/=> placeholder [placeholder])
        resolved (-> (aba/resolve-placeholders schema
                                               (fn [ref]
                                                 (when (= ref 'example/X)
                                                   s/Int)))
                     abc/canonicalize-schema)
        {:keys [input-schemas output-schema]} (into {} resolved)]
    (is (seq input-schemas))
    (is (= s/Int output-schema))
    (is (= '(=> Int [Int])
           (abc/schema-display-form resolved)))))

(deftest display-keeps-type-and-schema-domains-separate-test
  (let [type-var (at/->TypeVarT tp 'X)
        polymorphic-map (at/->MapT tp {(at/->GroundT tp :keyword 'Keyword)
                                       (at/->ForallT tp 'X (at/->FunT tp [(at/->FnMethodT tp [type-var]
                                                                                          (at/->SealedDynT tp type-var)
                                                                                          1
                                                                                          false
                                                                                          '[x])]))})]
    (is (= '{Keyword (forall X (=> (sealed X) X))}
           (abr/render-type-form polymorphic-map)))
    (is (= "hello"
           (abr/render-type-form (T (s/eq "hello")))))
    (is (= 'Int
           (abc/schema-display-form s/Int)))))

(deftest raw-schema-var-normalization-test
  (is (= s/Int
         (abc/canonicalize-schema #'BoundSchemaRef)))
  (is (= "Int"
         (abr/render-type (ab/schema->type tp #'BoundSchemaRef))))
  (is (= (sb/placeholder-schema 'skeptic.analysis.bridge-test/UnboundSchemaRef)
         (abc/canonicalize-schema #'UnboundSchemaRef)))
  (is (= 'skeptic.analysis.bridge-test/UnboundSchemaRef
         (->> #'UnboundSchemaRef
              (ab/schema->type tp)
             :ref)))
  (let [unbound-root (.getRawRoot ^clojure.lang.Var #'UnboundSchemaRef)]
    (is (= (sb/placeholder-schema 'skeptic.analysis.bridge-test/UnboundSchemaRef)
           (abc/canonicalize-schema unbound-root)))
    (is (= 'skeptic.analysis.bridge-test/UnboundSchemaRef
           (->> unbound-root
                (ab/schema->type tp)
               :ref))))
  (is (= [(sb/placeholder-schema 'skeptic.analysis.bridge-test/RecursiveSchemaRef)]
         (abc/canonicalize-schema #'RecursiveSchemaRef)))
  (let [recursive-type (ab/schema->type tp #'RecursiveSchemaRef)]
    (is (at/vector-type? recursive-type))
    (is (:homogeneous? recursive-type))
    (is (at/inf-cycle-type? (first (:items recursive-type))))
    (is (= 'skeptic.analysis.bridge-test/RecursiveSchemaRef
           (-> recursive-type :items first :ref)))))

(deftest recursive-collections-reduce-by-construction-test
  (let [ref 'skeptic.analysis.bridge-test/JoinedRecursiveSchemaRef
        expected-join (ato/union-type tp [(T s/Int)
                                          (at/->InfCycleT tp ref)
                                          (T s/Str)])
        joined-vector (T #'JoinedRecursiveSchemaRef)
        joined-seq (T #'RecursiveSeqRef)
        joined-set (T #'RecursiveSetRef)]
    (is (at/type=? (at/->InfCycleT tp 'skeptic.analysis.bridge-test/DirectRecursiveSchemaRef)
           (T #'DirectRecursiveSchemaRef)))
    (is (at/vector-type? joined-vector))
    (is (:homogeneous? joined-vector))
    (is (= expected-join (first (:items joined-vector))))
    (is (at/seq-type? joined-seq))
    (is (:homogeneous? joined-seq))
    (is (= (ato/union-type tp [(T s/Int)
                               (at/->InfCycleT tp 'skeptic.analysis.bridge-test/RecursiveSeqRef)
                               (T s/Str)])
           (first (:items joined-seq))))
    (is (at/set-type? joined-set))
    (is (:homogeneous? joined-set))
    (is (= #{(ato/union-type tp [(T s/Int)
                                 (at/->InfCycleT tp 'skeptic.analysis.bridge-test/RecursiveSetRef)
                                 (T s/Str)])}
           (:members joined-set)))))

(deftest broad-numeric-schemas-import-to-numeric-dyn-test
  (is (at/type=? (at/NumericDyn tp) (T s/Num)))
  (is (at/type=? (at/NumericDyn tp) (T java.lang.Number))))

(deftest admit-schema-defines-the-shared-schema-boundary-test
  (let [regex (ab/admit-schema #"^[a-z]+$")]
    (is (instance? java.util.regex.Pattern regex))
    (is (= "^[a-z]+$" (.pattern regex))))
  (is (= {:a s/Int}
         (ab/admit-schema {:a s/Int})))
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected schema value"
                        (ab/admit-schema (at/->GroundT tp :int 'Int)))))

(deftest schema-to-type-rejects-semantic-type-input-test
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected schema value"
                        (ab/schema->type tp (at/->GroundT tp :int 'Int)))))

(deftest canonicalize-schema-rejects-semantic-type-input-test
  (let [semantic-type (at/->GroundT tp :int 'Int)]
    (is (thrown-with-msg? IllegalArgumentException
                          #"Expected schema value"
                          (abc/canonicalize-schema semantic-type)))))

(deftest localize-and-strip-derived-types-test
  (let [type-var (at/->TypeVarT tp 'X)
        forall (at/->ForallT tp 'X (at/->FunT tp [(at/->FnMethodT tp [type-var]
                                                                  type-var
                                                                  1
                                                                  false
                                                                  '[x])]))
        sealed (at/->SealedDynT tp type-var)
        localized (abl/localize-value {:poly forall
                                       :sealed sealed})
        stripped (abr/strip-derived-types {:schema s/Int
                                           :type forall
                                           :output-type sealed})]
    (is (at/semantic-type-value? forall))
    (is (= forall (get localized :poly)))
    (is (= sealed (get localized :sealed)))
    (is (= {:schema s/Int
            :type forall
            :output-type sealed}
           stripped))))
