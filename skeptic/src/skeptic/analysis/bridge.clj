(ns skeptic.analysis.bridge
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at])
  (:import [clojure.lang IPersistentCollection]
           [schema.core One]))

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

(declare admit-schema)

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
    (if (sb/named? schema)
      (sb/de-named schema)
      schema)))

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

(defn- unary-child-result
  [build child-result]
  (import-result (build (:type child-result))
                 (:closed-refs child-result)))

(defn- collection-import-type
  [run {:keys [owner-ref prov] :as ctx} schemas fixed-build homogeneous-build]
  (let [child-results (mapv #(run (assoc ctx :schema %)) schemas)
        child-types (mapv :type child-results)
        closed-refs (merge-closed-refs child-results)
        type (if (and owner-ref (contains? closed-refs owner-ref))
               (homogeneous-build (ato/union-type prov child-types))
               (fixed-build child-types))]
    (import-result type closed-refs)))

(defn- map-import-type
  [run {:keys [prov] :as ctx} schema]
  (let [entry-results (mapv (fn [[k v]]
                              (let [key-result (run (assoc ctx :schema k))
                                    value-result (run (assoc ctx :schema v))]
                                {:entry [(:type key-result) (:type value-result)]
                                 :closed-refs (merge-closed-refs [key-result value-result])}))
                            schema)]
    (import-result
     (at/->MapT prov
                (into {}
                      (map :entry)
                      entry-results))
     (merge-closed-refs entry-results))))

(defn- function-import-type
  [run {:keys [prov] :as ctx} schema]
  (let [{:keys [input-schemas output-schema]} (into {} schema)
        output-result (run (assoc ctx :schema output-schema))
        method-results (mapv (fn [inputs]
                               (let [input-results (mapv #(run (assoc ctx :schema (fn-input-schema %)))
                                                         inputs)
                                     child-results (conj input-results output-result)
                                     names (mapv #(symbol (str "arg" %)) (range (count inputs)))]
                                 {:type (at/->FnMethodT prov
                                                        (mapv :type input-results)
                                                        (:type output-result)
                                                        (count inputs)
                                                        false
                                                        names)
                                  :closed-refs (merge-closed-refs child-results)}))
                             input-schemas)]
    (import-result
     (at/->FunT prov (mapv :type method-results))
     (merge-closed-refs (conj method-results output-result)))))

(defn- branch-import-type
  [run ctx schemas build]
  (let [child-results (mapv #(run (assoc ctx :schema %)) schemas)]
    (import-result (build (mapv :type child-results))
                   (merge-closed-refs child-results))))

(defn- conditional-import-type
  [run {:keys [prov] :as ctx} schema]
  (let [branch-results (mapv (fn [[pred branch]]
                               (let [branch-result (run (assoc ctx :schema branch))]
                                 {:branch [pred (:type branch-result)]
                                  :closed-refs (:closed-refs branch-result)}))
                             (:preds-and-schemas schema))]
    (import-result
     (at/->ConditionalT prov (mapv :branch branch-results))
     (merge-closed-refs branch-results))))

(defn- var-import-type
  [run {:keys [prov active-refs] :as ctx} schema-var]
  (let [var-ref (or (sb/qualified-var-symbol schema-var) schema-var)]
    (cond
      (contains? active-refs var-ref)
      (import-result (at/->InfCycleT prov var-ref) #{var-ref})

      (bound? schema-var)
      (run (assoc ctx
                  :schema @schema-var
                  :active-refs (conj active-refs var-ref)
                  :owner-ref var-ref))

      :else
      (import-result (at/->PlaceholderT prov var-ref)))))

(defn- import-schema-type*
  [run {:keys [schema prov] :as ctx}]
  (let [schema (one-step-schema-node schema)
        scalar-schema (sb/canonical-scalar-schema schema)]
    (cond
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
      (unary-child-result (partial at/->OptionalKeyT prov)
                          (run (assoc ctx :schema (:k schema))))

      (sb/eq? schema)
      (import-result (ato/exact-value-type prov (sb/de-eq schema)))

      (sb/constrained? schema)
      (refinement-import-type run ctx schema)

      (primitive-ground-schema? scalar-schema)
      (import-result (primitive-ground-type prov scalar-schema))

      (sb/fn-schema? schema)
      (function-import-type run ctx schema)

      (sb/maybe? schema)
      (unary-child-result (partial at/->MaybeT prov)
                          (run (assoc ctx :schema (:schema schema))))

      (sb/enum-schema? schema)
      (import-result (ato/union-type prov (mapv #(ato/exact-value-type prov %) (sb/de-enum schema))))

      (sb/join? schema)
      (branch-import-type run ctx (:schemas schema) #(ato/union-type prov %))

      (sb/either? schema)
      (branch-import-type run ctx (:schemas schema) #(ato/union-type prov %))

      (sb/conditional-schema? schema)
      (conditional-import-type run ctx schema)

      (sb/cond-pre? schema)
      (branch-import-type run ctx (:schemas schema) #(ato/union-type prov %))

      (sb/both? schema)
      (branch-import-type run ctx (:schemas schema) #(ato/intersection-type prov %))

      (sb/valued-schema? schema)
      (let [inner-result (run (assoc ctx :schema (:schema schema)))]
        (import-result (at/->ValueT prov (:type inner-result) (:value schema))
                       (:closed-refs inner-result)))

      (sb/variable? schema)
      (unary-child-result (partial at/->VarT prov)
                          (run (assoc ctx :schema (:schema schema))))

      (sb/plain-map-schema? schema)
      (map-import-type run ctx schema)

      (vector? schema)
      (collection-import-type run
                              ctx
                              schema
                              #(at/->VectorT prov % (= 1 (count %)))
                              (fn [joined]
                                (at/->VectorT prov [joined] true)))

      (set? schema)
      (collection-import-type run
                              ctx
                              (vec schema)
                              #(at/->SetT prov (into #{} %) (= 1 (count %)))
                              (fn [joined]
                                (at/->SetT prov #{joined} true)))

      (seq? schema)
      (collection-import-type run
                              ctx
                              (vec schema)
                              #(at/->SeqT prov % (= 1 (count %)))
                              (fn [joined]
                                (at/->SeqT prov [joined] true)))

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
  [prov schema]
  (letfn [(run [ctx]
            (import-schema-type* run ctx))]
    (:type (run {:schema schema
                 :active-refs #{}
                 :owner-ref nil
                 :prov prov}))))

(defn schema->type
  "Input must be schema-domain (e.g. from admitted declarations)."
  [prov schema]
  (import-schema-type prov (admit-schema schema)))
