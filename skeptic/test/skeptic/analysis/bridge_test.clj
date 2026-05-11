(ns skeptic.analysis.bridge-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.algebra :as aba]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.schema.discovery :as discovery]
            [skeptic.test-examples.contracts]
            [skeptic.test-examples.form-refs]
            [skeptic.test-helpers :refer [is-type= T tp]]
            [skeptic.typed-decls :as td])
  (:import [java.io File]))

(defn- ns-var-provs
  [ns-sym]
  (require ns-sym)
  (into {}
        (keep (fn [[_ v]]
                (when (and (var? v) (bound? v))
                  (let [qsym (sb/qualified-var-symbol v)]
                    [qsym (prov/make-provenance :schema qsym ns-sym (meta v) [] :clj)]))))
        (ns-interns ns-sym)))

(use-fixtures :each
  (fn [test-fn]
    (binding [ab/*var-provs* (merge (ns-var-provs 'skeptic.analysis.bridge-test)
                                    (ns-var-provs 'skeptic.test-examples.form-refs)
                                    (ns-var-provs 'skeptic.analysis.origin.schema))]
      (test-fn))))

(defn- form-refs-from-discovery
  [ns-sym source-file]
  (let [discovery-out (discovery/discover ns-sym source-file)
        acc (java.util.IdentityHashMap.)]
    (doseq [[_ {:keys [role form declared-sym]}] (:declarations discovery-out)
            :when (#{:s/defn :s/def :s/defschema} role)
            :let [v (ns-resolve (the-ns ns-sym) declared-sym)]
            :when (var? v)]
      (.put acc v form))
    acc))

(declare UnboundSchemaRef
         DirectRecursiveSchemaRef
         JoinedRecursiveSchemaRef
         RecursiveSeqRef
         RecursiveSetRef)

(def BoundSchemaRef s/Int)
(def NonSchemaLong 42)
(def AliasedSchema s/Int)
(defrecord IntakeRecord [x])
(def RecursiveSchemaRef [#'RecursiveSchemaRef])
(def DirectRecursiveSchemaRef #'DirectRecursiveSchemaRef)
(def JoinedRecursiveSchemaRef [(s/one s/Int 'a) (s/one s/Str 'b) #'JoinedRecursiveSchemaRef])
(def RecursiveSeqRef (list (s/one s/Int 'a) (s/one s/Str 'b) #'RecursiveSeqRef))
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
  (is (= "skeptic.analysis.bridge-test/BoundSchemaRef"
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
    (is (some? (:tail recursive-type)))
    (is (at/inf-cycle-type? (:tail recursive-type)))
    (is (= 'skeptic.analysis.bridge-test/RecursiveSchemaRef
           (-> recursive-type :tail :ref)))))

(deftest recursive-collections-reduce-by-construction-test
  (let [ref 'skeptic.analysis.bridge-test/JoinedRecursiveSchemaRef
        expected-join (ato/union-type tp [(T s/Int)
                                          (at/->InfCycleT tp ref)
                                          (T s/Str)])
        joined-vector (T #'JoinedRecursiveSchemaRef)
        joined-seq (T #'RecursiveSeqRef)
        joined-set (T #'RecursiveSetRef)]
    (is-type= (at/->InfCycleT tp 'skeptic.analysis.bridge-test/DirectRecursiveSchemaRef)
              (T #'DirectRecursiveSchemaRef))
    (is (at/vector-type? joined-vector))
    (is (some? (:tail joined-vector)))
    (is-type= expected-join (:tail joined-vector))
    (is (at/seq-type? joined-seq))
    (is (some? (:tail joined-seq)))
    (is-type= (ato/union-type tp [(T s/Int)
                                  (at/->InfCycleT tp 'skeptic.analysis.bridge-test/RecursiveSeqRef)
                                  (T s/Str)])
              (:tail joined-seq))
    (is (at/set-type? joined-set))
    (is (:homogeneous? joined-set))
    (is-type= (ato/union-type tp [(T s/Int)
                                  (at/->InfCycleT tp 'skeptic.analysis.bridge-test/RecursiveSetRef)
                                  (T s/Str)])
              (first (:members joined-set)))))

(deftest broad-numeric-schemas-import-to-numeric-dyn-test
  (is-type= (at/NumericDyn tp) (T s/Num))
  (is-type= (at/NumericDyn tp) (T java.lang.Number)))

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

(deftest localize-value-preserves-semantic-types-and-does-not-walk-payload-test
  (let [realized? (atom false)
        lazy-payload (map (fn [x] (reset! realized? true) x) [1 2 3])
        leaf (at/->AdapterLeafT tp :schema 'Display (constantly true) lazy-payload)
        value (at/->ValueT tp (at/Dyn tp) lazy-payload)]
    (is (identical? leaf (abl/localize-value leaf)))
    (is (identical? value (abl/localize-value value)))
    (is (false? @realized?))))

(deftest canonicalize-schema-resolves-vars-inside-plumatic-wrappers-test
  (is (= (s/maybe s/Int)    (abc/canonicalize-schema (s/maybe #'BoundSchemaRef))))
  (is (= (s/cond-pre s/Int) (abc/canonicalize-schema (s/cond-pre #'BoundSchemaRef))))
  (is (= (s/either s/Int)   (abc/canonicalize-schema (s/either #'BoundSchemaRef))))
  (is (= (s/both s/Int)     (abc/canonicalize-schema (s/both #'BoundSchemaRef))))
  (is (= (s/constrained s/Int even? 'even)
         (abc/canonicalize-schema (s/constrained #'BoundSchemaRef even? 'even))))
  (is (= (s/conditional even? s/Int)
         (abc/canonicalize-schema (s/conditional even? #'BoundSchemaRef))))
  (is (= {(s/optional-key s/Int) s/Any}
         (abc/canonicalize-schema {(s/optional-key #'BoundSchemaRef) s/Any}))))

(s/defschema NestedRefA [#{s/Int}])
(s/defschema NestedRefB {:inner #'NestedRefA})
(s/defschema RecR [#{(s/recursive #'RecR)}])
(s/defschema MyIntAlias s/Int)

(defn- build-var-provs-for-test
  [acc & vars]
  (reduce (fn [m v]
            (let [meta-m (meta v)
                  qsym (sb/qualified-var-symbol v)]
              (assoc m qsym (prov/make-provenance :schema qsym (some-> v .ns ns-name) meta-m [] :clj))))
          acc
          vars))

(deftest named-import-type-inline-named-schema-test
  (let [inline (s/named [#{s/Int}] 'Inline)
        result (ab/schema->type tp inline)
        inner-prov (prov/of result)]
    (is (= :schema (prov/source inner-prov)))
    (is (= 'Inline (:qualified-sym inner-prov)))))

(deftest nested-var-ref-carries-referenced-declaration-prov-test
  (let [var-provs (build-var-provs-for-test {} #'NestedRefA #'NestedRefB)
        result (binding [ab/*var-provs* var-provs]
                 (ab/schema->type tp #'NestedRefB))
        inner-val-type (first (vals (:entries result)))
        inner-prov (prov/of inner-val-type)]
    (is (= :schema (prov/source inner-prov)))
    (is (= (sb/qualified-var-symbol #'NestedRefA) (:qualified-sym inner-prov)))))

(deftest recursive-var-ref-prov-down-to-inf-cycle-test
  (let [var-provs (build-var-provs-for-test {} #'RecR)
        result (binding [ab/*var-provs* var-provs]
                 (ab/schema->type tp #'RecR))
        r-qsym (sb/qualified-var-symbol #'RecR)
        body-type (:tail result)]
    (is (= r-qsym (:qualified-sym (prov/of result))))
    (is (= :schema (prov/source (prov/of body-type))))
    (is (nil? (:qualified-sym (prov/of body-type))))))

(deftest caller-prov-preserved-when-no-var-provs-test
  (let [result (binding [ab/*var-provs* nil]
                 (ab/schema->type tp s/Int))]
    (is (= tp (prov/of result)))))

(deftest var-prov-used-when-var-provs-populated-test
  (let [var-provs (build-var-provs-for-test {} #'MyIntAlias)
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

(defn- entry-val-by-key
  [map-type k]
  (some (fn [[kt vt]] (when (= (:value kt) k) vt)) (:entries map-type)))

(deftest source-intake-map-slot-value-position-test
  (let [map-body-qsym (sb/qualified-var-symbol #'skeptic.test-examples.form-refs/MapBody)
        result (ab/schema->type tp {:result [s/Int]}
                                '{:result skeptic.test-examples.form-refs/MapBody})
        val-type (entry-val-by-key result :result)]
    (is (some? val-type))
    (is (= :schema (prov/source (prov/of val-type))))
    (is (= map-body-qsym (:qualified-sym (prov/of val-type))))))

(deftest source-intake-vector-index-position-test
  (let [vec-body-qsym (sb/qualified-var-symbol #'skeptic.test-examples.form-refs/VecBody)
        result (ab/schema->type tp [s/Int]
                                '[skeptic.test-examples.form-refs/VecBody])
        child-type (:tail result)]
    (is (some? child-type))
    (is (= :schema (prov/source (prov/of child-type))))
    (is (= vec-body-qsym (:qualified-sym (prov/of child-type))))))

(deftest source-intake-wrapper-node-unnamed-with-child-ref-test
  (let [map-body-qsym (sb/qualified-var-symbol #'skeptic.test-examples.form-refs/MapBody)
        result (ab/schema->type tp (s/maybe s/Int)
                                '(s/maybe skeptic.test-examples.form-refs/MapBody))
        p (prov/of result)]
    (is (= :inferred (prov/source p)))
    (is (nil? (:qualified-sym p)))
    (is (= map-body-qsym (-> p :refs first :qualified-sym)))))

(deftest source-intake-recursive-leaf-position-test
  (let [t (ab/import-schema-type tp #'skeptic.test-examples.form-refs/RX)]
    (is-type= (at/->GroundT tp :int 'Int) t)))

(deftest source-intake-self-recursive-test
  (let [t (ab/import-schema-type tp #'skeptic.test-examples.form-refs/Tree)]
    (is-type= (at/->MapT tp {(at/->ValueT tp (at/->GroundT tp :keyword 'Keyword) :children)
                             (at/->VectorT tp [] (at/->InfCycleT tp 'skeptic.test-examples.form-refs/Tree))})
              t)))

(deftest source-intake-conditional-with-recursion-test
  (let [t (ab/import-schema-type tp #'skeptic.analysis.origin.schema/Origin)
        map-key-lookup-branch (second (nth (:branches t) 2))
        root-slot (entry-val-by-key map-key-lookup-branch :root)]
    (is (at/conditional-type? t))
    (is (at/map-type? map-key-lookup-branch))
    (is (some? root-slot))
    (is (not (at/adapter-leaf-type? root-slot)))))

(deftest source-intake-class-symbol-total-test
  (let [result (ab/schema->type tp String 'String)]
    (is (= tp (prov/of result)))
    (is (= 'Str (abr/render-type-form result)))))

(deftest source-intake-defrecord-class-symbol-total-test
  (let [result (ab/schema->type tp IntakeRecord 'IntakeRecord)]
    (is (= tp (prov/of result)))
    (is (some? (abr/render-type-form result)))))

(deftest source-intake-no-override-total-cases-test
  (doseq [source-form ['clojure.core/+ 'clojure.core/defn
                       'skeptic.analysis.bridge-test/NonSchemaLong
                       'NoSuchVar 's/Int 'schema.core/Int]]
    (let [result (ab/schema->type tp s/Int source-form)]
      (is (= tp (prov/of result)) (str source-form)))))

(deftest source-intake-folds-plain-def-schema-alias-test
  (let [alias-qsym (sb/qualified-var-symbol #'AliasedSchema)
        result (ab/schema->type tp s/Int 'skeptic.analysis.bridge-test/AliasedSchema)]
    (is (= :schema (prov/source (prov/of result))))
    (is (= alias-qsym (:qualified-sym (prov/of result))))))

(deftest source-intake-folds-inline-named-source-form-test
  (let [result (ab/schema->type tp s/Int '(s/named s/Int InlineAlias))]
    (is (= :schema (prov/source (prov/of result))))
    (is (= 'InlineAlias (:qualified-sym (prov/of result))))))

(deftest source-intake-wrapper-children-keep-source-forms-test
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

(deftest source-intake-nil-source-form-unchanged-test
  (let [result (ab/schema->type tp s/Int)]
    (is (= tp (prov/of result)))))

(deftest source-intake-convert-desc-end-to-end-test
  (let [declared-var #'skeptic.test-examples.form-refs/fn-with-map-ann
        map-body-qsym (sb/qualified-var-symbol #'skeptic.test-examples.form-refs/MapBody)
        form-refs (doto (java.util.IdentityHashMap.) (.put declared-var '{:result skeptic.test-examples.form-refs/MapBody}))
        desc {:schema {:result [s/Int]} :arglists nil}
        result (binding [ab/*form-refs* form-refs]
                 (td/convert-desc 'skeptic.test-examples.form-refs
                                  'skeptic.test-examples.form-refs/fn-with-map-ann
                                  desc
                                  :clj))
        result-type (get (:dict result) 'skeptic.test-examples.form-refs/fn-with-map-ann)
        val-type (entry-val-by-key result-type :result)]
    (is (some? val-type))
    (is (= :schema (prov/source (prov/of val-type))))
    (is (= map-body-qsym (:qualified-sym (prov/of val-type))))))

(deftest source-descriptor-cycle-via-cond-pre-vector-terminates-test
  (let [form '(schema.core/cond-pre schema.core/Int [skeptic.analysis.bridge-test/BoundSchemaRef])
        form-refs (doto (java.util.IdentityHashMap.) (.put #'BoundSchemaRef form))
        descriptor (binding [ab/*form-refs* form-refs]
                     (#'ab/source-descriptor tp form))]
    (is (some? descriptor))
    (is (= form (:form descriptor)))))

(deftest source-descriptor-cycle-via-conditional-self-terminates-test
  (let [form '(schema.core/conditional clojure.core/even? skeptic.analysis.bridge-test/BoundSchemaRef
                                       clojure.core/odd? skeptic.analysis.bridge-test/BoundSchemaRef)
        form-refs (doto (java.util.IdentityHashMap.) (.put #'BoundSchemaRef form))
        descriptor (binding [ab/*form-refs* form-refs]
                     (#'ab/source-descriptor tp form))]
    (is (some? descriptor))
    (is (= form (:form descriptor)))))

(deftest source-descriptor-cycle-via-mutual-vars-terminates-test
  (let [form-a '(schema.core/cond-pre schema.core/Int [skeptic.analysis.bridge-test/AliasedSchema])
        form-b '(schema.core/cond-pre schema.core/Str [skeptic.analysis.bridge-test/BoundSchemaRef])
        form-refs (doto (java.util.IdentityHashMap.)
                    (.put #'BoundSchemaRef form-a)
                    (.put #'AliasedSchema form-b))
        descriptor (binding [ab/*form-refs* form-refs]
                     (#'ab/source-descriptor tp form-a))]
    (is (some? descriptor))
    (is (= form-a (:form descriptor)))))

(deftest source-descriptor-deeply-nested-maybe-stack-safe-test
  (let [n 5000
        form (reduce (fn [s _] (list 's/maybe s)) 's/Int (range n))
        descriptor (#'ab/source-descriptor tp form)]
    (is (some? descriptor))
    (is (= n (loop [d descriptor depth 0]
               (if-let [child (first (:children d))]
                 (recur child (inc depth))
                 depth))))))

(deftest source-descriptor-deeply-nested-vector-stack-safe-test
  (let [n 5000
        form (reduce (fn [v _] [v]) 's/Int (range n))
        descriptor (#'ab/source-descriptor tp form)]
    (is (some? descriptor))
    (is (= n (loop [d descriptor depth 0]
               (if-let [child (first (:children d))]
                 (recur child (inc depth))
                 depth))))))

(deftest source-descriptor-deeply-nested-map-stack-safe-test
  (let [n 5000
        form (reduce (fn [m _] {:k m}) 's/Int (range n))
        descriptor (#'ab/source-descriptor tp form)]
    (is (some? descriptor))
    (is (= n (loop [d descriptor depth 0]
               (if-let [child (get-in d [:map-entries :k :val])]
                 (recur child (inc depth))
                 depth))))))

(deftest source-intake-named-conditional-reference-keeps-branch-predicate-source-test
  (let [ns-sym 'skeptic.test-examples.contracts
        source-file (File. "test/skeptic/test_examples/contracts.clj")
        form-refs (form-refs-from-discovery ns-sym source-file)
        result (binding [*ns* (the-ns ns-sym)
                         ab/*form-refs* form-refs]
                 (td/typed-ns-results {} ns-sym :clj source-file))
        fn-type (get-in result [:dict 'skeptic.test-examples.contracts/chooses-conditional-success])
        input-type (-> fn-type at/fun-methods first at/fn-method-inputs first)
        pred-forms (mapv #(nth % 2) (:branches input-type))]
    (is (= 2 (count pred-forms)))
    (is (every? seq? pred-forms))
    (is (every? #(= 'fn* (first %)) pred-forms))
    (is (= #{:a :b} (set (filter keyword? (tree-seq coll? seq pred-forms)))))
    (is (= '#{choose} (set (filter #(= 'choose %) (tree-seq coll? seq pred-forms)))))))
