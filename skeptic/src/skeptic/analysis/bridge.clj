(ns skeptic.analysis.bridge
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov])
  (:import [clojure.lang IPersistentCollection]
           [schema.core One]))

(def ^:dynamic *annotation-refs* nil)
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

(defn primitive-ground-type
  [prov schema]
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

(defn- refinement-import-type
  [run {:keys [prov] :as ctx} schema]
  (let [base-result (run (assoc ctx :schema (sb/de-constrained schema)))]
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
  [ctx-prov ctor source-form child-result]
  (let [child-prov (prov/of (:type child-result))
        node-p (node-prov ctx-prov source-form [child-prov])]
    (import-result (ctor node-p (:type child-result))
                   (:closed-refs child-result))))

(defn- child-form-fn
  [source-form]
  (cond
    (vector? source-form) #(nth source-form % nil)
    (set? source-form) (let [v (vec source-form)] #(nth v % nil))
    (seq? source-form) (let [v (vec source-form)] #(nth v % nil))
    :else (fn [_i] nil)))

(defn- collection-import-type
  [run {:keys [owner-ref prov] :as ctx} schemas source-form fixed-ctor union-ctor]
  (let [inner-ctx (descend-ctx ctx)
        child-results (mapv (fn [i s]
                              (run (assoc inner-ctx :schema s :source-form ((child-form-fn source-form) i))))
                            (range) schemas)
        child-types (mapv :type child-results)
        child-provs (mapv prov/of child-types)
        closed-refs (merge-closed-refs child-results)
        node-p (node-prov prov source-form child-provs)
        type (if (and owner-ref (contains? closed-refs owner-ref))
               (let [joined-p (node-prov prov nil child-provs)]
                 (union-ctor node-p (ato/union-type joined-p child-types)))
               (fixed-ctor node-p child-types))]
    (import-result type closed-refs)))

(defn- map-import-type
  [run {:keys [prov] :as ctx} schema source-form]
  (let [parent-form (when (map? source-form) source-form)
        inner-ctx (descend-ctx ctx)
        entry-results (mapv (fn [[k v]]
                              (let [k-form (when parent-form (some #(when (= % k) %) (keys parent-form)))
                                    v-form (when parent-form (get parent-form k))
                                    key-result (run (assoc inner-ctx :schema k :source-form k-form))
                                    value-result (run (assoc inner-ctx :schema v :source-form v-form))]
                                {:entry [(:type key-result) (:type value-result)]
                                 :closed-refs (merge-closed-refs [key-result value-result])}))
                            schema)
        child-provs (mapcat (fn [{:keys [entry]}]
                              [(prov/of (first entry)) (prov/of (second entry))])
                            entry-results)
        node-p (node-prov prov source-form child-provs)]
    (import-result
     (at/->MapT node-p (into {} (map :entry) entry-results))
     (merge-closed-refs entry-results))))

(defn- input-forms-for-arity
  [descriptor n-inputs]
  (let [arglists (:arglists descriptor)
        k (if (contains? arglists n-inputs) n-inputs :varargs)]
    (get-in arglists [k :input-forms])))

(defn- function-import-type
  [run {:keys [prov] :as ctx} schema descriptor]
  (let [defn? (and (map? descriptor) (= :defn (:kind descriptor)))
        output-form (when defn? (:output-form descriptor))
        inner-ctx (descend-ctx ctx)
        {:keys [input-schemas output-schema]} (into {} schema)
        output-result (run (assoc inner-ctx :schema output-schema :source-form output-form))
        method-results (mapv (fn [inputs]
                               (let [input-forms (when defn? (input-forms-for-arity descriptor (count inputs)))
                                     input-results (vec (map-indexed
                                                          (fn [i in]
                                                            (run (assoc inner-ctx
                                                                        :schema (fn-input-schema in)
                                                                        :source-form (nth input-forms i nil))))
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
  [run {:keys [prov] :as ctx} schemas build]
  (let [inner-ctx (descend-ctx ctx)
        child-results (mapv #(run (assoc inner-ctx :schema %)) schemas)
        child-types (mapv :type child-results)
        node-p (node-prov prov nil (mapv prov/of child-types))]
    (import-result (build node-p child-types)
                   (merge-closed-refs child-results))))

(defn- conditional-import-type
  [run {:keys [prov] :as ctx} schema]
  (let [inner-ctx (descend-ctx ctx)
        branch-results (mapv (fn [[pred branch]]
                               (let [branch-result (run (assoc inner-ctx :schema branch))]
                                 {:branch [pred (:type branch-result)]
                                  :closed-refs (:closed-refs branch-result)}))
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

(defn- inline-named-name
  [form]
  (when (and (seq? form) (>= (count form) 3))
    (let [head (first form)]
      (when (or (= head 's/named) (= head 'schema.core/named))
        (let [name-form (nth form 2)]
          (cond
            (and (seq? name-form) (= 'quote (first name-form))) (second name-form)
            (symbol? name-form) name-form))))))

(defn- form-prov
  [form ctx-prov]
  (cond
    (symbol? form)
    (when-let [v (try (resolve form) (catch Exception _ nil))]
      (let [m (meta v)]
        (when (and (not (:macro m)) (bound? v))
          (let [val @v]
            (when (and (not (fn? val)) (not (sb/schema-literal? val)) (schema-domain? val))
              (prov/make-provenance :schema
                                    (sb/qualified-var-symbol v)
                                    (some-> v .ns ns-name)
                                    m))))))

    :else
    (when-let [name-sym (inline-named-name form)]
      (when (symbol? name-sym)
        (prov/make-provenance :schema
                              name-sym
                              (:declared-in ctx-prov)
                              (:var-meta ctx-prov))))))

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
  [ctx-prov source-form child-provs]
  (or (form-prov source-form ctx-prov)
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

(defn- descriptor-source-form
  [descriptor]
  (cond
    (nil? descriptor) nil
    (and (map? descriptor) (contains? descriptor :kind))
    (case (:kind descriptor)
      :def (:schema-form descriptor)
      :defschema (:schema-form descriptor)
      :defn descriptor)
    :else descriptor))

(defn- import-schema-type*
  [run {:keys [schema prov source-form] :as ctx}]
  (let [hit-prov (form-prov source-form prov)
        prov (or hit-prov prov)
        ctx (assoc ctx :prov prov :source-form nil)
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
      (unary-node-result prov at/->OptionalKeyT source-form
                         (run (assoc (descend-ctx ctx) :schema (:k schema))))

      (sb/eq? schema)
      (import-result (ato/exact-value-type prov (sb/de-eq schema)))

      (sb/constrained? schema)
      (refinement-import-type run ctx schema)

      (primitive-ground-schema? scalar-schema)
      (import-result (primitive-ground-type prov scalar-schema))

      (sb/fn-schema? schema)
      (function-import-type run ctx schema source-form)

      (sb/maybe? schema)
      (unary-node-result prov at/->MaybeT source-form
                         (run (assoc (descend-ctx ctx) :schema (:schema schema))))

      (sb/enum-schema? schema)
      (import-result (ato/union-type prov (mapv #(ato/exact-value-type prov %) (sb/de-enum schema))))

      (sb/join? schema)
      (branch-import-type run ctx (:schemas schema) ato/union-type)

      (sb/either? schema)
      (branch-import-type run ctx (:schemas schema) ato/union-type)

      (sb/conditional-schema? schema)
      (conditional-import-type run ctx schema)

      (sb/cond-pre? schema)
      (branch-import-type run ctx (:schemas schema) ato/union-type)

      (sb/both? schema)
      (branch-import-type run ctx (:schemas schema) ato/intersection-type)

      (sb/valued-schema? schema)
      (let [inner-result (run (assoc (descend-ctx ctx) :schema (:schema schema)))
            child-prov (prov/of (:type inner-result))
            node-p (node-prov prov source-form [child-prov])]
        (import-result (at/->ValueT node-p (:type inner-result) (:value schema))
                       (:closed-refs inner-result)))

      (sb/variable? schema)
      (unary-node-result prov at/->VarT source-form
                         (run (assoc (descend-ctx ctx) :schema (:schema schema))))

      (sb/plain-map-schema? schema)
      (map-import-type run ctx schema source-form)

      (vector? schema)
      (collection-import-type run ctx schema source-form
                              (fn [p ts] (at/->VectorT p ts (= 1 (count ts))))
                              (fn [p joined] (at/->VectorT p [joined] true)))

      (set? schema)
      (collection-import-type run ctx (vec schema) source-form
                              (fn [p ts] (at/->SetT p (into #{} ts) (= 1 (count ts))))
                              (fn [p joined] (at/->SetT p #{joined} true)))

      (seq? schema)
      (collection-import-type run ctx (vec schema) nil
                              (fn [p ts] (at/->SeqT p ts (= 1 (count ts))))
                              (fn [p joined] (at/->SeqT p [joined] true)))

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

(defn import-schema-type
  "Input must be in the schema domain."
  ([prov schema] (import-schema-type prov schema nil))
  ([prov schema source-form]
   (letfn [(run [ctx]
             (import-schema-type* run ctx))]
     (:type (run {:schema schema
                  :active-refs #{}
                  :owner-ref nil
                  :prov prov
                  :source-form source-form})))))

(defn schema->type
  "Input must be schema-domain (e.g. from admitted declarations)."
  ([prov schema] (schema->type prov schema nil))
  ([prov schema descriptor]
   (import-schema-type prov (admit-schema schema) (descriptor-source-form descriptor))))
