(ns skeptic.analysis.bridge
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.predicates :as predicates]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs])
  (:import [clojure.lang IPersistentCollection]
           [schema.core One]))

(def ^:dynamic *var-provs* nil)
(def ^:dynamic *form-refs* nil)

(defn broad-dynamic-schema?
  [schema]
  (contains? #{s/Any
               Object}
             schema))

(defn primitive-ground-schema?
  [schema]
  (let [schema (sb/canonical-scalar-schema schema)]
    (cond
      (= schema s/Int) true
      (= schema s/Str) true
      (= schema s/Keyword) true
      (= schema s/Symbol) true
      (= schema s/Bool) true
      (and (class? schema)
           (.isAssignableFrom IPersistentCollection ^Class schema))
      false
      (and (class? schema)
           (not (broad-dynamic-schema? schema)))
      true
      :else false)))

(s/defn primitive-ground-type :- (s/maybe ats/SemanticType)
  [prov :- provs/Provenance
   schema :- s/Any]
  (let [schema (sb/canonical-scalar-schema schema)]
    (cond
      (= schema s/Int) (at/->GroundT prov :int 'Int)
      (= schema s/Str) (at/->GroundT prov :str 'Str)
      (= schema s/Keyword) (at/->GroundT prov :keyword 'Keyword)
      (= schema s/Symbol) (at/->GroundT prov :symbol 'Symbol)
      (= schema s/Bool) (at/->GroundT prov :bool 'Bool)
      (and (class? schema)
           (not (broad-dynamic-schema? schema)))
      (at/->GroundT prov {:class schema} (abc/schema-explain schema)))))

(defn- invalid-schema-input
  [value]
  (throw (IllegalArgumentException.
          (format "Expected schema value: %s" (pr-str value)))))

(declare admit-schema schema-domain? node-prov composite-node-prov descend-ctx)

(defn- import-result
  ([type]
   (import-result type #{}))
  ([type closed-refs]
   {:type type
    :closed-refs closed-refs}))

(defn- merge-closed-refs
  [results]
  (into #{} (mapcat :closed-refs) results))

(defn- one-step-schema-node
  [schema]
  (let [schema (cond
                 (at/same-class-name? schema "clojure.lang.Var$Unbound")
                 (at/read-instance-field schema "v")

                 :else
                 schema)]
    (when (at/semantic-type-value? schema)
      (invalid-schema-input schema))
    schema))

(defn- fn-input-schema
  [one]
  (cond
    (instance? One one)
    (:schema (into {} one))

    (and (map? one) (contains? one :schema))
    (:schema one)

    :else
    one))

(defn- source-descriptor
  [prov form]
  (letfn [(build [form]
            (let [children (cond
                             (map? form) nil
                             (vector? form) (mapv build form)
                             (set? form) (mapv build (vec form))
                             (seq? form) (mapv build (rest form))
                             :else [])
                  map-entries (when (map? form)
                                (into {}
                                      (map (fn [[k v]]
                                             [k {:key (build k) :val (build v)}]))
                                      form))]
              {:form form
               :named-prov (source-named-prov prov form)
               :children children
               :map-entries map-entries
               :conditional-branches (conditional-branch-sources form)}))
          (resolve-symbol [form]
            (when (symbol? form)
              (try (resolve form) (catch Exception _ nil))))
          (source-named-prov [ctx-prov form]
            (or (var-source-prov form)
                (inline-named-source-prov ctx-prov form)))
          (var-source-prov [form]
            (when-let [v (resolve-symbol form)]
              (when (instance? clojure.lang.Var v)
                (let [m (meta v)
                      qsym (sb/qualified-var-symbol v)]
                  (when (and qsym
                             (not= 'schema.core (some-> v .ns ns-name))
                             (not (:macro m))
                             (bound? v))
                    (let [val @v]
                      (when (and (not (fn? val))
                                 (not (sb/schema-literal? val))
                                 (schema-domain? val))
                        (prov/make-provenance :schema
                                              qsym
                                              (some-> v .ns ns-name)
                                              m))))))))
          (inline-named-source-prov [ctx-prov form]
            (when-let [name-sym (inline-named-name form)]
              (when (symbol? name-sym)
                (prov/make-provenance :schema
                                      name-sym
                                      (:declared-in ctx-prov)
                                      (:var-meta ctx-prov)))))
          (inline-named-name [form]
            (when (and (seq? form) (>= (count form) 3))
              (let [head (first form)]
                (when (or (= head 's/named) (= head 'schema.core/named))
                  (let [name-form (nth form 2)]
                    (cond
                      (and (seq? name-form) (= 'quote (first name-form))) (second name-form)
                      (symbol? name-form) name-form))))))
          (conditional-branch-sources [form]
            (when (seq? form)
              (mapv (fn [[pred branch]]
                      {:pred-form pred
                       :schema-source (build branch)})
                    (partition 2 (rest form)))))]
    (build form)))

(defn- descriptor-source
  [prov descriptor]
  (cond
    (nil? descriptor) nil
    (and (map? descriptor) (contains? descriptor :kind))
    (case (:kind descriptor)
      :def (source-descriptor prov (:schema-form descriptor))
      :defschema (source-descriptor prov (:schema-form descriptor))
      :defn {:kind :defn
             :output-source (source-descriptor prov (:output-form descriptor))
             :arglists (into {}
                             (map (fn [[k v]]
                                    [k (assoc v
                                              :input-sources
                                              (mapv #(source-descriptor prov %)
                                                    (:input-forms v)))]))
                             (:arglists descriptor))}
      nil)
    :else (source-descriptor prov descriptor)))

(defn- child-source
  [source i]
  (get-in source [:children i]))

(defn- unary-source
  [source]
  (child-source source 0))

(defn- refinement-import-type
  [run {:keys [prov] :as ctx} schema source]
  (let [base-result (run (assoc ctx
                                :schema (sb/de-constrained schema)
                                :source (unary-source source)))]
    (import-result
     (at/->RefinementT prov
                       (:type base-result)
                       (abc/schema-display-form schema)
                       (fn [value]
                         (= (sb/check-if-schema schema value) sb/plumatic-valid))
                       {:adapter :schema
                        :kind :constrained})
     (:closed-refs base-result))))

(defn- adapter-leaf-import-type
  [prov schema]
  (import-result
   (at/->AdapterLeafT prov
                      :schema
                      (abc/schema-display-form schema)
                      (fn [value]
                        (= (sb/check-if-schema schema value) sb/plumatic-valid))
                      {:source-schema schema})))

(defn- unary-node-result
  [ctx-prov ctor source child-result]
  (let [child-prov (prov/of (:type child-result))
        node-p (node-prov ctx-prov source [child-prov])]
    (import-result (ctor node-p (:type child-result))
                   (:closed-refs child-result))))

(defn- collection-import-type
  [run {:keys [owner-ref prov] :as ctx} schemas source fixed-ctor union-ctor]
  (let [inner-ctx (descend-ctx ctx)
        child-results (mapv (fn [i s]
                              (run (assoc inner-ctx :schema s :source (child-source source i))))
                            (range) schemas)
        child-types (mapv :type child-results)
        child-provs (mapv prov/of child-types)
        closed-refs (merge-closed-refs child-results)
        node-p (node-prov prov source child-provs)
        type (if (and owner-ref (contains? closed-refs owner-ref))
               (let [joined-p (node-prov prov nil child-provs)]
                 (union-ctor node-p (ato/union-type joined-p child-types)))
               (fixed-ctor node-p child-types))]
    (import-result type closed-refs)))

(defn- map-import-type
  [run {:keys [prov] :as ctx} schema source]
  (let [inner-ctx (descend-ctx ctx)
        entry-results (mapv (fn [[k v]]
                              (let [entry-source (get-in source [:map-entries k])
                                    key-result (run (assoc inner-ctx :schema k :source (:key entry-source)))
                                    value-result (run (assoc inner-ctx :schema v :source (:val entry-source)))]
                                {:entry [(:type key-result) (:type value-result)]
                                 :closed-refs (merge-closed-refs [key-result value-result])}))
                            schema)
        child-provs (mapcat (fn [{:keys [entry]}]
                              [(prov/of (first entry)) (prov/of (second entry))])
                            entry-results)
        node-p (node-prov prov source child-provs)]
    (import-result
     (at/->MapT node-p (into {} (map :entry) entry-results))
     (merge-closed-refs entry-results))))

(defn- input-sources-for-arity
  [source n-inputs]
  (let [arglists (:arglists source)
        k (if (contains? arglists n-inputs) n-inputs :varargs)]
    (get-in arglists [k :input-sources])))

(defn- function-import-type
  [run {:keys [prov] :as ctx} schema source]
  (let [defn? (and (map? source) (= :defn (:kind source)))
        output-source (when defn? (:output-source source))
        inner-ctx (descend-ctx ctx)
        {:keys [input-schemas output-schema]} (into {} schema)
        output-result (run (assoc inner-ctx :schema output-schema :source output-source))
        method-results (mapv (fn [inputs]
                               (let [input-sources (when defn? (input-sources-for-arity source (count inputs)))
                                     input-results (vec (map-indexed
                                                          (fn [i in]
                                                            (run (assoc inner-ctx
                                                                        :schema (fn-input-schema in)
                                                                        :source (nth input-sources i nil))))
                                                          inputs))
                                     child-results (conj input-results output-result)
                                     input-provs (mapv (comp prov/of :type) input-results)
                                     method-p (node-prov prov nil (conj input-provs (prov/of (:type output-result))))
                                     names (mapv #(symbol (str "arg" %)) (range (count inputs)))]
                                 {:type (at/->FnMethodT method-p
                                                        (mapv :type input-results)
                                                        (:type output-result)
                                                        (count inputs)
                                                        false
                                                        names)
                                  :closed-refs (merge-closed-refs child-results)}))
                             input-schemas)
        method-provs (mapv (comp prov/of :type) method-results)
        fun-p (node-prov prov nil method-provs)]
    (import-result
     (at/->FunT fun-p (mapv :type method-results))
     (merge-closed-refs (conj method-results output-result)))))

(defn- branch-import-type
  [run {:keys [prov] :as ctx} schemas source build]
  (let [inner-ctx (descend-ctx ctx)
        child-results (mapv (fn [i schema]
                              (run (assoc inner-ctx
                                          :schema schema
                                          :source (child-source source i))))
                            (range)
                            schemas)
        child-types (mapv :type child-results)
        node-p (node-prov prov nil (mapv prov/of child-types))]
    (import-result (build node-p child-types)
                   (merge-closed-refs child-results))))

(defn- conditional-import-type
  [run {:keys [prov] :as ctx} schema source]
  (let [inner-ctx (descend-ctx ctx)
        branch-results (mapv (fn [i [pred branch]]
                               (let [branch-source (get-in source [:conditional-branches i])
                                     branch-result (run (assoc inner-ctx
                                                               :schema branch
                                                               :source (:schema-source branch-source)))]
                                 {:branch [pred (:type branch-result) (:pred-form branch-source)]
                                  :closed-refs (:closed-refs branch-result)}))
                             (range)
                             (:preds-and-schemas schema))
        branch-provs (mapv (fn [{:keys [branch]}] (prov/of (second branch))) branch-results)
        node-p (node-prov prov nil branch-provs)]
    (import-result
     (at/->ConditionalT node-p (mapv :branch branch-results))
     (merge-closed-refs branch-results))))

(defn- named-import-type
  [run {:keys [prov] :as ctx} schema]
  (let [name-sym (sb/named-name schema)
        named-prov (prov/make-provenance :schema
                                         name-sym
                                         (:declared-in prov)
                                         (:var-meta prov))]
    (run (assoc ctx :schema (sb/de-named schema) :prov named-prov))))

(defn- var-import-type
  [run {:keys [prov active-refs] :as ctx} schema-var]
  (let [var-ref (or (sb/qualified-var-symbol schema-var) schema-var)]
    (cond
      (contains? active-refs var-ref)
      (import-result (at/->InfCycleT prov var-ref) #{var-ref})

      (bound? schema-var)
      (let [hit (when *var-provs*
                  (.get ^java.util.IdentityHashMap *var-provs* schema-var))]
        (run (assoc ctx
                    :schema @schema-var
                    :prov (or hit prov)
                    :active-refs (conj active-refs var-ref)
                    :owner-ref var-ref)))

      :else
      (import-result (at/->PlaceholderT prov var-ref)))))

(defn- composite-node-prov
  [ctx-prov child-provs]
  (prov/make-provenance (:source ctx-prov)
                        nil
                        (:declared-in ctx-prov)
                        (:var-meta ctx-prov)
                        (vec (distinct (keep identity child-provs)))))

(def ^:private schema-foldable-sources
  #{:schema :malli :type-override})

(defn- schema-named-prov?
  [p]
  (and p (:qualified-sym p) (contains? schema-foldable-sources (:source p))))

(defn- node-prov
  [ctx-prov source child-provs]
  (or (:named-prov source)
      (when (schema-named-prov? ctx-prov) ctx-prov)
      (when (seq child-provs)
        (composite-node-prov ctx-prov child-provs))
      ctx-prov))

(defn- descend-ctx
  [ctx]
  (let [p (:prov ctx)]
    (if (schema-named-prov? p)
      (assoc ctx :prov (assoc p :qualified-sym nil))
      ctx)))

(defn- import-schema-type*
  [run {:keys [schema prov source] :as ctx}]
  (let [prov (node-prov prov source [])
        ctx (assoc ctx :prov prov :source nil)
        schema (one-step-schema-node schema)
        scalar-schema (sb/canonical-scalar-schema schema)]
    (cond
      (sb/named? schema)
      (named-import-type run ctx schema)

      (instance? clojure.lang.Var schema)
      (var-import-type run ctx schema)

      (nil? schema)
      (import-result (at/->MaybeT prov (at/Dyn prov)))

      (= schema sb/Bottom)
      (import-result (at/BottomType prov))

      (sb/placeholder-schema? schema)
      (import-result (at/->PlaceholderT prov (sb/placeholder-ref schema)))

      (or (= scalar-schema s/Num)
          (and (class? scalar-schema) (= scalar-schema java.lang.Number)))
      (import-result (at/NumericDyn prov))

      (broad-dynamic-schema? scalar-schema)
      (import-result (at/Dyn prov))

      (instance? One schema)
      (run (assoc ctx :schema (:schema (into {} schema))))

      (sb/schema-literal? schema)
      (import-result (ato/exact-value-type prov schema))

      (s/optional-key? schema)
      (unary-node-result prov at/->OptionalKeyT source
                         (run (assoc (descend-ctx ctx)
                                     :schema (:k schema)
                                     :source (unary-source source))))

      (sb/eq? schema)
      (import-result (ato/exact-value-type prov (sb/de-eq schema)))

      (sb/constrained? schema)
      (refinement-import-type run ctx schema source)

      (primitive-ground-schema? scalar-schema)
      (import-result (primitive-ground-type prov schema))

      (sb/fn-schema? schema)
      (function-import-type run ctx schema source)

      (sb/maybe? schema)
      (unary-node-result prov at/->MaybeT source
                         (run (assoc (descend-ctx ctx)
                                     :schema (:schema schema)
                                     :source (unary-source source))))

      (sb/enum-schema? schema)
      (import-result (ato/union-type prov (mapv #(ato/exact-value-type prov %) (sb/de-enum schema))))

      (sb/join? schema)
      (branch-import-type run ctx (:schemas schema) source ato/union-type)

      (sb/either? schema)
      (branch-import-type run ctx (:schemas schema) source ato/union-type)

      (sb/conditional-schema? schema)
      (conditional-import-type run ctx schema source)

      (sb/cond-pre? schema)
      (branch-import-type run ctx (:schemas schema) source ato/union-type)

      (sb/both? schema)
      (branch-import-type run ctx (:schemas schema) source ato/intersection-type)

      (sb/valued-schema? schema)
      (let [inner-result (run (assoc (descend-ctx ctx)
                                     :schema (:schema schema)
                                     :source (unary-source source)))
            child-prov (prov/of (:type inner-result))
            node-p (node-prov prov source [child-prov])]
        (import-result (at/->ValueT node-p (:type inner-result) (:value schema))
                       (:closed-refs inner-result)))

      (sb/variable? schema)
      (unary-node-result prov at/->VarT source
                         (run (assoc (descend-ctx ctx)
                                     :schema (:schema schema)
                                     :source (unary-source source))))

      (sb/plain-map-schema? schema)
      (map-import-type run ctx schema source)

      (vector? schema)
      (collection-import-type run ctx schema source
                              (fn [p ts] (at/->VectorT p ts (= 1 (count ts))))
                              (fn [p joined] (at/->VectorT p [joined] true)))

      (set? schema)
      (collection-import-type run ctx (vec schema) source
                              (fn [p ts] (at/->SetT p (into #{} ts) (= 1 (count ts))))
                              (fn [p joined] (at/->SetT p #{joined} true)))

      (seq? schema)
      (collection-import-type run ctx (vec schema) source
                              (fn [p ts] (at/->SeqT p ts (= 1 (count ts))))
                              (fn [p joined] (at/->SeqT p [joined] true)))

      (sb/pred? schema)
      (let [pred-sym (sb/de-pred schema)
            qualified (predicates/resolve-predicate-symbol pred-sym)]
        (if qualified
          (import-result (predicates/witness-type qualified prov))
          (adapter-leaf-import-type prov schema)))

      :else
      (adapter-leaf-import-type prov schema))))

(defn- admit-schema*
  [run {:keys [schema active-refs]}]
  (let [schema (one-step-schema-node schema)
        scalar-schema (sb/canonical-scalar-schema schema)]
    (cond
      (instance? clojure.lang.Var schema)
      (let [var-ref (or (sb/qualified-var-symbol schema) schema)]
        (when (and (not (contains? active-refs var-ref))
                   (bound? schema))
          (run {:schema @schema
                :active-refs (conj active-refs var-ref)}))
        schema)

      (nil? schema)
      schema

      (= schema sb/Bottom)
      schema

      (sb/placeholder-schema? schema)
      schema

      (or (= scalar-schema s/Num)
          (and (class? scalar-schema) (= scalar-schema java.lang.Number)))
      schema

      (broad-dynamic-schema? scalar-schema)
      schema

      (instance? One schema)
      (do
        (run {:schema (:schema (into {} schema))
              :active-refs active-refs})
        schema)

      (sb/schema-literal? schema)
      schema

      (s/optional-key? schema)
      (do
        (run {:schema (:k schema)
              :active-refs active-refs})
        schema)

      (sb/eq? schema)
      schema

      (sb/constrained? schema)
      (do
        (run {:schema (sb/de-constrained schema)
              :active-refs active-refs})
        schema)

      (primitive-ground-schema? scalar-schema)
      schema

      (sb/fn-schema? schema)
      (let [{:keys [input-schemas output-schema]} (into {} schema)]
        (run {:schema output-schema
              :active-refs active-refs})
        (doseq [inputs input-schemas
                input inputs]
          (run {:schema (fn-input-schema input)
                :active-refs active-refs}))
        schema)

      (sb/maybe? schema)
      (do
        (run {:schema (:schema schema)
              :active-refs active-refs})
        schema)

      (sb/enum-schema? schema)
      schema

      (sb/join? schema)
      (do
        (doseq [member (:schemas schema)]
          (run {:schema member
                :active-refs active-refs}))
        schema)

      (sb/either? schema)
      (do
        (doseq [member (:schemas schema)]
          (run {:schema member
                :active-refs active-refs}))
        schema)

      (sb/conditional-schema? schema)
      (do
        (doseq [[_pred branch] (:preds-and-schemas schema)]
          (run {:schema branch
                :active-refs active-refs}))
        schema)

      (sb/cond-pre? schema)
      (do
        (doseq [member (:schemas schema)]
          (run {:schema member
                :active-refs active-refs}))
        schema)

      (sb/both? schema)
      (do
        (doseq [member (:schemas schema)]
          (run {:schema member
                :active-refs active-refs}))
        schema)

      (sb/valued-schema? schema)
      (do
        (run {:schema (:schema schema)
              :active-refs active-refs})
        schema)

      (sb/variable? schema)
      (do
        (run {:schema (:schema schema)
              :active-refs active-refs})
        schema)

      (sb/plain-map-schema? schema)
      (do
        (doseq [[k v] schema]
          (run {:schema k
                :active-refs active-refs})
          (run {:schema v
                :active-refs active-refs}))
        schema)

      (vector? schema)
      (do
        (doseq [item schema]
          (run {:schema item
                :active-refs active-refs}))
        schema)

      (set? schema)
      (do
        (doseq [item schema]
          (run {:schema item
                :active-refs active-refs}))
        schema)

      (seq? schema)
      (do
        (doseq [item schema]
          (run {:schema item
                :active-refs active-refs}))
        schema)

      (sb/pred? schema)
      schema

      :else
      schema)))

(defn admit-schema
  "Validate and admit a value into Skeptic's schema domain. Returns the
  schema-domain value in the shape expected by `schema->type`."
  [schema]
  (letfn [(run [ctx]
            (admit-schema* run ctx))]
    (run {:schema schema
          :active-refs #{}})))

(defn schema-domain?
  [schema]
  (try
    (some? (admit-schema schema))
    (catch IllegalArgumentException _e
      false)))

(s/defn import-schema-type :- ats/SemanticType
  "Input must be in the schema domain."
  ([prov   :- provs/Provenance
    schema :- s/Any]
   (import-schema-type prov schema nil))
  ([prov   :- provs/Provenance
    schema :- s/Any
    source :- s/Any]
   (letfn [(run [ctx]
             (import-schema-type* run ctx))]
     (:type (run {:schema schema
                  :active-refs #{}
                  :owner-ref nil
                  :prov prov
                  :source source})))))

(s/defn schema->type :- ats/SemanticType
  "Input must be schema-domain (e.g. from admitted declarations)."
  ([prov   :- provs/Provenance
    schema :- s/Any]
   (schema->type prov schema nil))
  ([prov       :- provs/Provenance
    schema     :- s/Any
    descriptor :- s/Any]
   (import-schema-type prov (admit-schema schema) (descriptor-source prov descriptor))))
