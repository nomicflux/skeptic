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
            [skeptic.provenance :as prov]
            [skeptic.schema.collect :as collect]
            [skeptic.test-examples.form-refs]
            [skeptic.test-helpers :refer [T tp]]
            [skeptic.typed-decls :as td])
  (:import [java.util IdentityHashMap]))

(declare UnboundSchemaRef
         DirectRecursiveSchemaRef
         JoinedRecursiveSchemaRef
         RecursiveSeqRef
         RecursiveSetRef)

(def BoundSchemaRef s/Int)
(def NonSchemaLong 42)
(def AliasedSchema s/Int)
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
    (is (at/type=? expected-join (first (:items joined-vector))))
    (is (at/seq-type? joined-seq))
    (is (:homogeneous? joined-seq))
    (is (at/type=? (ato/union-type tp [(T s/Int)
                                        (at/->InfCycleT tp 'skeptic.analysis.bridge-test/RecursiveSeqRef)
                                        (T s/Str)])
                   (first (:items joined-seq))))
    (is (at/set-type? joined-set))
    (is (:homogeneous? joined-set))
    (is (at/type=? (ato/union-type tp [(T s/Int)
                                        (at/->InfCycleT tp 'skeptic.analysis.bridge-test/RecursiveSetRef)
                                        (T s/Str)])
                   (first (:members joined-set))))))

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

(s/defschema NestedRefA [#{s/Int}])
(s/defschema NestedRefB {:inner #'NestedRefA})
(s/defschema RecR [#{(s/recursive #'RecR)}])
(s/defschema MyIntAlias s/Int)

(defn- build-var-provs-for-test!
  [^IdentityHashMap acc & vars]
  (doseq [v vars
          :let [m (meta v)
                qsym (sb/qualified-var-symbol v)]]
    (.put acc v (prov/make-provenance :schema qsym (some-> v .ns ns-name) m)))
  acc)

(deftest named-import-type-inline-named-schema-test
  (let [inline (s/named [#{s/Int}] 'Inline)
        result (ab/schema->type tp inline)
        inner-prov (prov/of result)]
    (is (= :schema (prov/source inner-prov)))
    (is (= 'Inline (:qualified-sym inner-prov)))))

(deftest nested-var-ref-carries-referenced-declaration-prov-test
  (let [var-provs (build-var-provs-for-test! (IdentityHashMap.) #'NestedRefA #'NestedRefB)
        result (binding [ab/*var-provs* var-provs]
                 (ab/schema->type tp #'NestedRefB))
        inner-val-type (first (vals (:entries result)))
        inner-prov (prov/of inner-val-type)]
    (is (= :schema (prov/source inner-prov)))
    (is (= (sb/qualified-var-symbol #'NestedRefA) (:qualified-sym inner-prov)))))

(deftest recursive-var-ref-prov-down-to-inf-cycle-test
  (let [var-provs (build-var-provs-for-test! (IdentityHashMap.) #'RecR)
        result (binding [ab/*var-provs* var-provs]
                 (ab/schema->type tp #'RecR))
        r-qsym (sb/qualified-var-symbol #'RecR)
        body-type (first (:items result))]
    (is (= r-qsym (:qualified-sym (prov/of result))))
    (is (= :schema (prov/source (prov/of body-type))))
    (is (nil? (:qualified-sym (prov/of body-type))))))

(deftest caller-prov-preserved-when-no-var-provs-test
  (let [result (binding [ab/*var-provs* nil]
                 (ab/schema->type tp s/Int))]
    (is (= tp (prov/of result)))))

(deftest var-prov-used-when-var-provs-populated-test
  (let [var-provs (build-var-provs-for-test! (IdentityHashMap.) #'MyIntAlias)
        result (binding [ab/*var-provs* var-provs]
                 (ab/schema->type tp #'MyIntAlias))
        alias-qsym (sb/qualified-var-symbol #'MyIntAlias)]
    (is (= :schema (prov/source (prov/of result))))
    (is (= alias-qsym (:qualified-sym (prov/of result))))))

(deftest singleton-non-collision-test
  (let [result (binding [ab/*var-provs* nil]
                 (ab/schema->type tp s/Int))
        alias-qsym (sb/qualified-var-symbol #'MyIntAlias)]
    (is (not= alias-qsym (:qualified-sym (prov/of result))))))

(deftest build-var-provs-excludes-non-schema-vars-test
  (let [test-ns (create-ns 'skeptic.analysis.bridge-test.var-provs)
        schema-var (intern test-ns 'SchemaVar {:a s/Int})
        non-schema-var (intern test-ns 'NonSchemaVar 42)
        acc (IdentityHashMap.)]
    (alter-meta! schema-var assoc :schema true)
    (#'collect/build-var-provs! acc)
    (doseq [[v _] acc]
      (is (:schema (meta v))))
    (is (contains? (set (keys acc)) schema-var))
    (is (not (contains? (set (keys acc)) non-schema-var)))))

(defn- entry-val-by-key
  [map-type k]
  (some (fn [[kt vt]] (when (= (:value kt) k) vt)) (:entries map-type)))

(deftest form-prov-map-slot-value-position-test
  (let [map-body-qsym (sb/qualified-var-symbol #'skeptic.test-examples.form-refs/MapBody)
        result (ab/schema->type tp {:result [s/Int]}
                                '{:result skeptic.test-examples.form-refs/MapBody})
        val-type (entry-val-by-key result :result)]
    (is (some? val-type))
    (is (= :schema (prov/source (prov/of val-type))))
    (is (= map-body-qsym (:qualified-sym (prov/of val-type))))))

(deftest form-prov-vector-index-position-test
  (let [vec-body-qsym (sb/qualified-var-symbol #'skeptic.test-examples.form-refs/VecBody)
        result (ab/schema->type tp [s/Int]
                                '[skeptic.test-examples.form-refs/VecBody])
        child-type (first (:items result))]
    (is (some? child-type))
    (is (= :schema (prov/source (prov/of child-type))))
    (is (= vec-body-qsym (:qualified-sym (prov/of child-type))))))

(deftest form-prov-non-symbol-form-no-override-test
  (let [map-body-qsym (sb/qualified-var-symbol #'skeptic.test-examples.form-refs/MapBody)
        result (ab/schema->type tp (s/maybe s/Int)
                                '(s/maybe skeptic.test-examples.form-refs/MapBody))
        p (prov/of result)]
    (is (= :inferred (prov/source p)))
    (is (nil? (:qualified-sym p)))
    (is (= map-body-qsym (-> p :refs first :qualified-sym)))))

(deftest form-prov-class-symbol-falls-through-test
  (let [result (ab/schema->type tp String 'String)]
    (is (= tp (prov/of result)))
    (is (= 'String (abr/render-type-form result)))))

(deftest form-prov-non-schema-var-falls-through-test
  (let [result (ab/schema->type tp s/Int 'clojure.core/+)]
    (is (= tp (prov/of result)))))

(deftest form-prov-skips-literal-value-var-test
  (let [result (ab/schema->type tp s/Int 'skeptic.analysis.bridge-test/NonSchemaLong)]
    (is (= tp (prov/of result)))))

(deftest form-prov-folds-plain-def-schema-alias-test
  (let [alias-qsym (sb/qualified-var-symbol #'AliasedSchema)
        result (ab/schema->type tp s/Int 'skeptic.analysis.bridge-test/AliasedSchema)]
    (is (= :schema (prov/source (prov/of result))))
    (is (= alias-qsym (:qualified-sym (prov/of result))))))

(deftest form-prov-wrapper-children-keep-source-forms-test
  (let [alias-qsym (sb/qualified-var-symbol #'AliasedSchema)]
    (is (= (list 'optional-key alias-qsym)
           (abr/render-type-form
            (ab/schema->type tp
                             (s/optional-key s/Int)
                             '(s/optional-key skeptic.analysis.bridge-test/AliasedSchema)))))
    (is (= (list 'var alias-qsym)
           (abr/render-type-form
            (ab/schema->type tp
                             (sb/variable s/Int)
                             '(skeptic.analysis.schema-base/variable
                               skeptic.analysis.bridge-test/AliasedSchema)))))))

(deftest form-prov-nil-source-form-unchanged-test
  (let [result (ab/schema->type tp s/Int)]
    (is (= tp (prov/of result)))))

(deftest form-prov-convert-desc-end-to-end-test
  (let [declared-var #'skeptic.test-examples.form-refs/fn-with-map-ann
        map-body-qsym (sb/qualified-var-symbol #'skeptic.test-examples.form-refs/MapBody)
        form-refs (doto (IdentityHashMap.) (.put declared-var '{:result skeptic.test-examples.form-refs/MapBody}))
        desc {:schema {:result [s/Int]} :arglists nil}
        result (binding [ab/*form-refs* form-refs]
                 (td/convert-desc 'skeptic.test-examples.form-refs
                                  'skeptic.test-examples.form-refs/fn-with-map-ann
                                  desc))
        result-type (get (:dict result) 'skeptic.test-examples.form-refs/fn-with-map-ann)
        val-type (entry-val-by-key result-type :result)]
    (is (some? val-type))
    (is (= :schema (prov/source (prov/of val-type))))
    (is (= map-body-qsym (:qualified-sym (prov/of val-type))))))
