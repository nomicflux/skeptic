(ns skeptic.analysis.bridge
  (:require [clojure.set :as set]
            [schema.core :as s]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at])
  (:import [schema.core Both CondPre ConditionalSchema Constrained Either EnumSchema EqSchema FnSchema Maybe NamedSchema One Schema]))

(def ^:dynamic *error-context*
  nil)

(defn compact-context-map
  [m]
  (into {}
        (remove (comp nil? val))
        m))

(defmacro with-error-context
  [context & body]
  `(binding [*error-context* (merge *error-context*
                                    (compact-context-map ~context))]
     ~@body))

(declare schema-explain
         render-schema
         canonicalize-schema
         canonicalize-schema*
         canonicalize-output-schema
         canonicalize-entry-fn-schema
         union-like-branches
         both-components
         localize-schema-value
         de-maybe-type
         render-type-form
         render-type)

(defn schema?
  [s]
  (let [s (localize-schema-value s)]
    (cond
      (nil? s) true
      (sb/schema-literal? s) true
      (sb/custom-schema? s) true
      (instance? Schema s) true
      (class? s) true
      (s/optional-key? s) (schema? (:k s))
      (instance? One s) (let [m (try (into {} s)
                                     (catch Exception _e nil))]
                          (and (map? m)
                               (schema? (:schema m))))
      (and (map? s)
           (not (record? s))
           (not (at/semantic-type-value? s)))
      (every? (fn [[k v]]
                (and (schema? k)
                     (schema? v)))
              s)
      (vector? s) (every? schema? s)
      (set? s) (and (= 1 (count s))
                    (every? schema? s))
      (seq? s) (every? schema? s)
      :else false)))

(declare normalize-type
         union-type)

(defn de-maybe-type
  [type]
  (let [type (normalize-type type)]
    (cond
      (at/maybe-type? type)
      (:inner type)

      (at/union-type? type)
      (union-type (map (fn [member]
                         (if (at/maybe-type? member)
                           (:inner member)
                           member))
                       (:members type)))

      :else
      type)))

(defn flatten-join-members
  [types]
  (->> types
       (map canonicalize-schema)
       (mapcat (fn [schema]
                 (if (sb/join? schema)
                   (:schemas schema)
                   [schema])))
       set))

(defn nil-bearing-join
  [types]
  (let [types (flatten-join-members types)
        nil-bearing? (or (contains? types nil)
                         (some sb/maybe? types))
        types (disj types nil)
        {maybe-types true
         plain-types false} (group-by sb/maybe? types)
        maybe-bases (->> maybe-types
                         (map (comp canonicalize-schema sb/de-maybe))
                         set)
        maybe-bases (if (and (contains? maybe-bases s/Any)
                             (seq (concat plain-types
                                          (disj maybe-bases s/Any))))
                      (disj maybe-bases s/Any)
                      maybe-bases)]
    {:nil-bearing? nil-bearing?
     :types (into (set plain-types) maybe-bases)}))

(defn schema-join
  [[t1 & _r :as types]]
  (let [{:keys [nil-bearing? types]}
        (nil-bearing-join (cond->> types (not (set? types)) (into #{})))]
    (cond
      (empty? types)
      (if nil-bearing?
        (s/maybe s/Any)
        s/Any)

      (= 1 (count types))
      (let [schema (first types)]
        (if nil-bearing?
          (s/maybe schema)
          schema))

      :else
      (let [schema (apply sb/join types)]
        (if nil-bearing?
          (s/maybe schema)
          schema)))))

(defn schema-explain
  [schema]
  (cond
    (sb/bottom-schema? schema)
    'Bottom

    (sb/schema-literal? schema)
    schema

    (s/optional-key? schema)
    (list 'optional-key (schema-explain (:k schema)))

    (instance? One schema)
    (let [m (try (into {} schema)
                 (catch Exception _e nil))]
      (if (map? m)
        (list 'one
              (schema-explain (:schema m))
              (:name m))
        schema))

    (sb/join? schema)
    (into #{} (map (fn [member]
                     (if (or (schema? member)
                             (class? member))
                       (schema-explain member)
                       member))
                   (:schemas schema)))

    (sb/valued-schema? schema)
    (str (:value schema) " : " (schema-explain (:schema schema)))

    (sb/variable? schema)
    (list "#'" (schema-explain (:schema schema)))

    (or (schema? schema)
        (class? schema))
    (s/explain schema)

    :else schema))

(defn canonicalize-one
  [one]
  (let [m (try (into {} one)
               (catch Exception _e nil))]
    (if (and (map? m)
             (contains? m :schema))
      (s/one (canonicalize-schema (:schema m))
             (:name m))
      (canonicalize-schema one))))

(defn canonicalize-map-key
  [k]
  (if (s/optional-key? k)
    (s/optional-key (canonicalize-schema (:k k)))
    (canonicalize-schema k)))

(defn canonicalize-entry
  [entry]
  (cond
    (nil? entry) nil
    (not (map? entry)) entry
    :else
    (cond-> entry
      (contains? entry :schema) (update :schema canonicalize-schema)
      (contains? entry :output) (update :output canonicalize-schema)
      (contains? entry :expected-arglist) (update :expected-arglist #(mapv canonicalize-schema %))
      (contains? entry :actual-arglist) (update :actual-arglist #(mapv canonicalize-schema %))
      (contains? entry :locals) (update :locals (fn [locals]
                                                  (into {}
                                                        (map (fn [[k v]]
                                                               [k (canonicalize-entry v)]))
                                                        locals)))
      (contains? entry :arglists) (update :arglists (fn [arglists]
                                                      (into {}
                                                            (map (fn [[k v]]
                                                                   [k (canonicalize-entry v)]))
                                                            arglists)))
      (contains? entry :schema) canonicalize-entry-fn-schema)))

(defn canonicalize-fn-schema
  [schema]
  (let [{:keys [input-schemas output-schema]} (into {} schema)]
    (s/make-fn-schema (canonicalize-schema output-schema)
                      (mapv (fn [inputs]
                              (mapv canonicalize-one inputs))
                            input-schemas))))

(defn canonicalize-entry-fn-schema
  [entry]
  (if (and (contains? entry :schema)
           (sb/fn-schema? (:schema entry)))
    (assoc entry :schema (canonicalize-fn-schema (:schema entry)))
    entry))

(declare localize-schema-value*)

(defn localize-schema-value
  [value]
  (localize-schema-value* value #{}))

(defn localize-schema-value*
  [value seen-vars]
  (cond
    (nil? value) nil
    (at/same-class-name? value "clojure.lang.Var$Unbound")
    (localize-schema-value* (at/read-instance-field value "v") seen-vars)
    (instance? clojure.lang.Var value)
    (let [var-ref (or (sb/qualified-var-symbol value) value)]
      (cond
        (contains? seen-vars var-ref) (sb/placeholder-schema var-ref)
        (bound? value) (localize-schema-value* @value (conj seen-vars var-ref))
        :else (sb/placeholder-schema var-ref)))
    (sb/bottom-schema? value) sb/Bottom
    (sb/join? value)
    (apply sb/join (map #(localize-schema-value* % seen-vars) (:schemas value)))
    (at/same-class-name? value "skeptic.analysis.schema.Join")
    (apply sb/join (map #(localize-schema-value* % seen-vars)
                     (at/read-instance-field value "schemas")))
    (sb/valued-schema? value)
    (sb/valued-schema (localize-schema-value* (:schema value) seen-vars)
                   (localize-schema-value* (:value value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.ValuedSchema")
    (sb/valued-schema (localize-schema-value* (at/read-instance-field value "schema") seen-vars)
                   (localize-schema-value* (at/read-instance-field value "value") seen-vars))
    (sb/variable? value)
    (sb/variable (localize-schema-value* (:schema value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.Variable")
    (sb/variable (localize-schema-value* (at/read-instance-field value "schema") seen-vars))
    (at/dyn-type? value) at/Dyn
    (at/bottom-type? value) at/BottomType
    (at/ground-type? value)
    (at/->GroundT (:ground value) (:display-form value))
    (at/same-class-name? value "skeptic.analysis.schema.GroundT")
    (at/->GroundT (at/read-instance-field value "ground")
               (at/read-instance-field value "display_form"))
    (at/refinement-type? value)
    (at/->RefinementT (localize-schema-value* (:base value) seen-vars)
                   (:display-form value)
                   (:accepts? value)
                   (localize-schema-value* (:adapter-data value) seen-vars))
    (at/adapter-leaf-type? value)
    (at/->AdapterLeafT (:adapter value)
                    (:display-form value)
                    (:accepts? value)
                    (localize-schema-value* (:adapter-data value) seen-vars))
    (at/optional-key-type? value)
    (at/->OptionalKeyT (localize-schema-value* (:inner value) seen-vars))
    (at/fn-method-type? value)
    (at/->FnMethodT (localize-schema-value* (:inputs value) seen-vars)
                 (localize-schema-value* (:output value) seen-vars)
                 (:min-arity value)
                 (:variadic? value))
    (at/same-class-name? value "skeptic.analysis.schema.FnMethodT")
    (at/->FnMethodT (localize-schema-value* (at/read-instance-field value "inputs") seen-vars)
                 (localize-schema-value* (at/read-instance-field value "output") seen-vars)
                 (at/read-instance-field value "min_arity")
                 (at/read-instance-field value "variadic_QMARK_"))
    (at/fun-type? value)
    (at/->FunT (localize-schema-value* (:methods value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.FunT")
    (at/->FunT (localize-schema-value* (at/read-instance-field value "methods") seen-vars))
    (at/maybe-type? value)
    (at/->MaybeT (localize-schema-value* (:inner value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.MaybeT")
    (at/->MaybeT (localize-schema-value* (at/read-instance-field value "inner") seen-vars))
    (at/union-type? value)
    (at/->UnionT (localize-schema-value* (:members value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.UnionT")
    (at/->UnionT (localize-schema-value* (at/read-instance-field value "members") seen-vars))
    (at/intersection-type? value)
    (at/->IntersectionT (localize-schema-value* (:members value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.IntersectionT")
    (at/->IntersectionT (localize-schema-value* (at/read-instance-field value "members") seen-vars))
    (at/map-type? value)
    (at/->MapT (localize-schema-value* (:entries value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.MapT")
    (at/->MapT (localize-schema-value* (at/read-instance-field value "entries") seen-vars))
    (at/vector-type? value)
    (at/->VectorT (localize-schema-value* (:items value) seen-vars)
               (:homogeneous? value))
    (at/same-class-name? value "skeptic.analysis.schema.VectorT")
    (at/->VectorT (localize-schema-value* (at/read-instance-field value "items") seen-vars)
               (at/read-instance-field value "homogeneous_QMARK_"))
    (at/set-type? value)
    (at/->SetT (localize-schema-value* (:members value) seen-vars)
            (:homogeneous? value))
    (at/same-class-name? value "skeptic.analysis.schema.SetT")
    (at/->SetT (localize-schema-value* (at/read-instance-field value "members") seen-vars)
            (at/read-instance-field value "homogeneous_QMARK_"))
    (at/seq-type? value)
    (at/->SeqT (localize-schema-value* (:items value) seen-vars)
            (:homogeneous? value))
    (at/same-class-name? value "skeptic.analysis.schema.SeqT")
    (at/->SeqT (localize-schema-value* (at/read-instance-field value "items") seen-vars)
            (at/read-instance-field value "homogeneous_QMARK_"))
    (at/var-type? value)
    (at/->VarT (localize-schema-value* (:inner value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.VarT")
    (at/->VarT (localize-schema-value* (at/read-instance-field value "inner") seen-vars))
    (at/placeholder-type? value)
    (at/->PlaceholderT (localize-schema-value* (:ref value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.PlaceholderT")
    (at/->PlaceholderT (localize-schema-value* (at/read-instance-field value "ref") seen-vars))
    (at/value-type? value)
    (at/->ValueT (localize-schema-value* (:inner value) seen-vars)
              (localize-schema-value* (:value value) seen-vars))
    (at/same-class-name? value "skeptic.analysis.schema.ValueT")
    (at/->ValueT (localize-schema-value* (at/read-instance-field value "inner") seen-vars)
              (localize-schema-value* (at/read-instance-field value "value") seen-vars))
    (at/type-var-type? value)
    (at/->TypeVarT (:name value))
    (at/forall-type? value)
    (at/->ForallT (:binder value)
               (localize-schema-value* (:body value) seen-vars))
    (at/sealed-dyn-type? value)
    (at/->SealedDynT (localize-schema-value* (:ground value) seen-vars))
    (vector? value) (mapv #(localize-schema-value* % seen-vars) value)
    (set? value) (into #{} (map #(localize-schema-value* % seen-vars)) value)
    (and (map? value) (not (record? value)))
    (into {}
          (map (fn [[k v]]
                 [(localize-schema-value* k seen-vars)
                  (localize-schema-value* v seen-vars)]))
          value)
    (seq? value) (doall (map #(localize-schema-value* % seen-vars) value))
    :else value))

(defn canonicalize-schema*
  [schema {:keys [constrained->base?]}]
  (let [schema (localize-schema-value schema)]
    (cond
    (nil? schema) nil
    (sb/named? schema) (canonicalize-schema* (sb/de-named schema)
                                          {:constrained->base? constrained->base?})
    (sb/placeholder-schema? schema) schema
    (sb/bottom-schema? schema) sb/Bottom
    (at/semantic-type-value? schema) schema
    (sb/fn-schema? schema) (canonicalize-fn-schema schema)
    (instance? One schema) (canonicalize-one schema)
    (sb/maybe? schema) (s/maybe (canonicalize-schema* (:schema schema)
                                                   {:constrained->base? constrained->base?}))
    (sb/constrained? schema) (if constrained->base?
                           (canonicalize-schema* (sb/de-constrained schema)
                                                 {:constrained->base? true})
                           (s/constrained (canonicalize-schema* (sb/de-constrained schema)
                                                                {:constrained->base? false})
                                          (:postcondition schema)
                                          (:post-name schema)))
    (sb/either? schema) (apply s/either
                            (map #(canonicalize-schema* %
                                                        {:constrained->base? constrained->base?})
                                 (:schemas schema)))
    (sb/conditional-schema? schema) (let [branches (mapcat (fn [[pred branch]]
                                                          [pred (canonicalize-schema* branch
                                                                                     {:constrained->base? constrained->base?})])
                                                        (:preds-and-schemas schema))
                                       args (cond-> (vec branches)
                                              (:error-symbol schema) (conj (:error-symbol schema)))]
                                   (apply s/conditional args))
    (sb/cond-pre? schema) (apply s/cond-pre
                              (map #(canonicalize-schema* %
                                                          {:constrained->base? constrained->base?})
                                   (:schemas schema)))
    (sb/both? schema) (apply s/both
                          (map #(canonicalize-schema* %
                                                      {:constrained->base? constrained->base?})
                               (:schemas schema)))
    (sb/join? schema) (schema-join (set (map #(canonicalize-schema* %
                                                                {:constrained->base? constrained->base?})
                                          (:schemas schema))))
    (sb/valued-schema? schema) (sb/valued-schema (canonicalize-schema* (:schema schema)
                                                                 {:constrained->base? constrained->base?})
                                           (:value schema))
    (sb/variable? schema) (sb/variable (canonicalize-schema* (:schema schema)
                                                       {:constrained->base? constrained->base?}))
    (contains? #{s/Int s/Str s/Keyword s/Symbol s/Bool}
               (sb/canonical-scalar-schema schema))
    (sb/canonical-scalar-schema schema)
    (record? schema) schema
    (map? schema) (into {}
                       (map (fn [[k v]]
                              [(if (s/optional-key? k)
                                 (s/optional-key (canonicalize-schema* (:k k)
                                                                       {:constrained->base? constrained->base?}))
                                 (canonicalize-schema* k {:constrained->base? constrained->base?}))
                               (canonicalize-schema* v {:constrained->base? constrained->base?})]))
                       schema)
    (vector? schema) (mapv #(canonicalize-schema* % {:constrained->base? constrained->base?}) schema)
    (set? schema) (into #{} (map #(canonicalize-schema* % {:constrained->base? constrained->base?})) schema)
    (seq? schema) (doall (map #(canonicalize-schema* % {:constrained->base? constrained->base?}) schema))
    :else (sb/canonical-scalar-schema schema))))

(defn canonicalize-schema
  [schema]
  (canonicalize-schema* schema {:constrained->base? false}))

(defn canonicalize-output-schema
  [schema]
  (canonicalize-schema* schema {:constrained->base? false}))

(declare type-domain-value?
         normalize-type
         import-schema-type
         schema->type)

(defn nil-bearing-type-members
  [members]
  (->> members
       (map normalize-type)
       (mapcat (fn [member]
                 (cond
                   (at/union-type? member) (:members member)
                   :else [member])))
       ((fn [members]
          (let [nil-bearing? (some at/maybe-type? members)
                {maybe-members true
                 plain-members false} (group-by at/maybe-type? members)
                maybe-bases (->> maybe-members
                                 (map :inner)
                                 set)
                maybe-bases (if (and (contains? maybe-bases at/Dyn)
                                     (seq (concat plain-members
                                                  (disj maybe-bases at/Dyn))))
                              (disj maybe-bases at/Dyn)
                              maybe-bases)]
            {:nil-bearing? (boolean nil-bearing?)
             :members (into (set plain-members) maybe-bases)})))))

(defn normalize-intersection-members
  [members]
  (->> members
       (map normalize-type)
       (mapcat (fn [member]
                 (cond
                   (at/intersection-type? member) (:members member)
                   :else [member])))
       set))

(defn union-type
  [members]
  (let [{:keys [nil-bearing? members]} (nil-bearing-type-members members)
        base (cond
               (empty? members) at/Dyn
               (= 1 (count members)) (first members)
               :else (at/->UnionT members))]
    (if nil-bearing?
      (at/->MaybeT base)
      base)))

(defn intersection-type
  [members]
  (let [members (normalize-intersection-members members)]
    (cond
      (empty? members) at/Dyn
      (= 1 (count members)) (first members)
      :else (at/->IntersectionT members))))

(defn broad-dynamic-schema?
  [schema]
  (contains? (set [s/Any
                   s/Num
                   Number
                   java.lang.Number
                   Object
                   java.lang.Object])
             schema))

(defn- schema-display-form
  [schema]
  (let [schema (canonicalize-schema schema)]
    (when-not (schema? schema)
      (throw (IllegalArgumentException.
              (format "Not a valid Schema-domain value: %s" (pr-str schema)))))
    (schema-explain schema)))

(defn render-schema
  [schema]
  (some-> schema
          schema-display-form
          pr-str))

(defn- import-display-form
  [schema]
  (schema-display-form schema))

(defn primitive-ground-type
  [schema]
  (let [schema (sb/canonical-scalar-schema schema)]
    (cond
      (= schema s/Int) (at/->GroundT :int 'Int)
      (= schema s/Str) (at/->GroundT :str 'Str)
      (= schema s/Keyword) (at/->GroundT :keyword 'Keyword)
      (= schema s/Symbol) (at/->GroundT :symbol 'Symbol)
      (= schema s/Bool) (at/->GroundT :bool 'Bool)
      (and (class? schema)
           (not (broad-dynamic-schema? schema)))
      (at/->GroundT {:class schema} (schema-explain schema))
      :else nil)))

(defn literal-ground-type
  [value]
  (cond
    (integer? value) (at/->GroundT :int 'Int)
    (string? value) (at/->GroundT :str 'Str)
    (keyword? value) (at/->GroundT :keyword 'Keyword)
    (symbol? value) (at/->GroundT :symbol 'Symbol)
    (boolean? value) (at/->GroundT :bool 'Bool)
    :else nil))

(defn exact-value-import-type
  [value]
  (at/->ValueT (or (literal-ground-type value) at/Dyn) value))

(defn type-domain-value?
  [value]
  (let [value (localize-schema-value value)]
    (cond
      (sb/placeholder-schema? value) false
      (at/semantic-type-value? value) true
      (nil? value) true
      (sb/schema-literal? value) true
      (s/optional-key? value) (type-domain-value? (:k value))
      (and (map? value)
           (not (record? value))
           (not (contains? value sb/custom-schema-tag-key)))
      (every? (fn [[k v]]
                (and (type-domain-value? k)
                     (type-domain-value? v)))
              value)
      (vector? value) (every? type-domain-value? value)
      (set? value) (every? type-domain-value? value)
      (seq? value) (every? type-domain-value? value)
      :else false)))

(defn normalize-type
  [value]
  (let [value (localize-schema-value value)]
    (cond
      (at/semantic-type-value? value) value
      (nil? value) (at/->MaybeT at/Dyn)
      (sb/schema-literal? value) (exact-value-import-type value)
      (s/optional-key? value) (at/->OptionalKeyT (normalize-type (:k value)))
      (and (map? value) (not (record? value)))
      (at/->MapT (into {}
                    (map (fn [[k v]]
                           [(normalize-type k)
                            (normalize-type v)]))
                    value))
      (vector? value) (at/->VectorT (mapv normalize-type value) (= 1 (count value)))
      (set? value) (at/->SetT (into #{} (map normalize-type) value) (= 1 (count value)))
      (seq? value) (at/->SeqT (mapv normalize-type value) (= 1 (count value)))
      :else value)))

(defn refinement-import-type
  [schema]
  (at/->RefinementT (schema->type (sb/de-constrained schema))
                 (import-display-form schema)
                 (fn [value]
                   (= (sb/check-if-schema schema value) ::schema-valid))
                 {:adapter :schema
                  :kind :constrained}))

(defn adapter-leaf-import-type
  [schema]
  (at/->AdapterLeafT :schema
                  (import-display-form schema)
                  (fn [value]
                    (= (sb/check-if-schema schema value) ::schema-valid))
                  {:source-schema schema}))

(defn import-schema-type
  [schema]
  (let [schema (localize-schema-value schema)]
    (when-not (schema? schema)
      (throw (IllegalArgumentException.
              (format "Expected Schema-domain value: %s" (pr-str schema)))))
    (let [schema (canonicalize-schema schema)]
      (cond
        (nil? schema) (at/->MaybeT at/Dyn)
        (= schema sb/Bottom) at/BottomType
        (sb/placeholder-schema? schema) (at/->PlaceholderT (sb/placeholder-ref schema))
        (broad-dynamic-schema? schema) at/Dyn
        (instance? One schema) (import-schema-type (or (:schema (try (into {} schema)
                                                                     (catch Exception _e {})))
                                                       s/Any))
        (sb/schema-literal? schema) (exact-value-import-type schema)
        (s/optional-key? schema) (at/->OptionalKeyT (import-schema-type (:k schema)))
        (sb/eq? schema) (exact-value-import-type (sb/de-eq schema))
        (sb/constrained? schema) (refinement-import-type schema)
        (primitive-ground-type schema) (primitive-ground-type schema)

        (sb/fn-schema? schema)
        (let [{:keys [input-schemas output-schema]} (into {} schema)
              output-type (import-schema-type output-schema)
              methods (mapv (fn [inputs]
                              (at/->FnMethodT (mapv (fn [one]
                                                   (let [m (try (into {} one)
                                                                (catch Exception _e {}))]
                                                     (import-schema-type (or (:schema m) s/Any))))
                                                 inputs)
                                           output-type
                                           (count inputs)
                                           false))
                            input-schemas)]
          (at/->FunT methods))

        (sb/maybe? schema) (at/->MaybeT (import-schema-type (:schema schema)))
        (sb/enum-schema? schema) (union-type (map exact-value-import-type (sb/de-enum schema)))
        (sb/join? schema) (union-type (map import-schema-type (:schemas schema)))
        (sb/either? schema) (union-type (map import-schema-type (:schemas schema)))
        (sb/conditional-schema? schema) (union-type (map (comp import-schema-type second) (:preds-and-schemas schema)))
        (sb/cond-pre? schema) (union-type (map import-schema-type (:schemas schema)))
        (sb/both? schema) (intersection-type (map import-schema-type (:schemas schema)))
        (sb/valued-schema? schema) (at/->ValueT (import-schema-type (:schema schema)) (:value schema))
        (sb/variable? schema) (at/->VarT (import-schema-type (:schema schema)))

        (sb/plain-map-schema? schema)
        (at/->MapT (into {}
                       (map (fn [[k v]]
                              [(import-schema-type k)
                               (import-schema-type v)]))
                       schema))

        (vector? schema)
        (at/->VectorT (mapv import-schema-type schema) (= 1 (count schema)))

        (set? schema)
        (at/->SetT (into #{} (map import-schema-type) schema) (= 1 (count schema)))

        (seq? schema)
        (at/->SeqT (mapv import-schema-type schema) (= 1 (count schema)))

        :else
        (adapter-leaf-import-type schema)))))

(defn schema->type
  [value]
  (let [value (localize-schema-value value)]
    (cond
      (sb/placeholder-schema? value) (import-schema-type value)
      (type-domain-value? value) (normalize-type value)
      (schema? value) (import-schema-type value)
      (and (map? value) (not (record? value)))
      (at/->MapT (into {}
                    (map (fn [[k v]]
                           [(schema->type k)
                            (schema->type v)]))
                    value))
      (vector? value) (at/->VectorT (mapv schema->type value) (= 1 (count value)))
      (set? value) (at/->SetT (into #{} (map schema->type) value) (= 1 (count value)))
      (seq? value) (at/->SeqT (mapv schema->type value) (= 1 (count value)))
      :else
      (throw (IllegalArgumentException.
              (format "Not a valid type-domain or Schema-domain value: %s"
                      (pr-str value)))))))

(declare render-type-form)

(defn render-fn-input-form
  [method]
  (let [inputs (mapv render-type-form (:inputs method))]
    (if (:variadic? method)
      (concat (take (:min-arity method) inputs)
              ['& (drop (:min-arity method) inputs)])
      inputs)))

(defn render-type-form
  [type]
  (let [type (normalize-type type)]
    (cond
      (at/dyn-type? type) 'Any
      (at/bottom-type? type) 'Bottom
      (at/ground-type? type) (:display-form type)
      (at/refinement-type? type) (:display-form type)
      (at/adapter-leaf-type? type) (:display-form type)
      (at/optional-key-type? type) (list 'optional-key (render-type-form (:inner type)))
      (at/value-type? type) (:value type)
      (at/type-var-type? type) (:name type)
      (at/forall-type? type) (list 'forall (:binder type) (render-type-form (:body type)))
      (at/sealed-dyn-type? type) (list 'sealed (render-type-form (:ground type)))
      (at/fn-method-type? type) (list* '=> (render-type-form (:output type)) (render-fn-input-form type))
      (at/fun-type? type)
      (if (= 1 (count (:methods type)))
        (render-type-form (first (:methods type)))
        (list* '=>* (map render-type-form (:methods type))))
      (at/maybe-type? type) (list 'maybe (render-type-form (:inner type)))
      (at/union-type? type) (list* 'union (map render-type-form (sort-by pr-str (:members type))))
      (at/intersection-type? type) (list* 'intersection (map render-type-form (sort-by pr-str (:members type))))
      (at/map-type? type)
      (into {}
            (map (fn [[k v]]
                   [(render-type-form k)
                    (render-type-form v)]))
            (:entries type))
      (at/vector-type? type) (mapv render-type-form (:items type))
      (at/set-type? type) (into #{} (map render-type-form) (:members type))
      (at/seq-type? type) (doall (map render-type-form (:items type)))
      (at/var-type? type) (list 'var (render-type-form (:inner type)))
      (at/placeholder-type? type) (at/placeholder-display-form (:ref type))
      :else type)))

(defn render-type
  [type]
  (some-> type
          render-type-form
          pr-str))

(defn display-form
  [value]
  (let [value (localize-schema-value value)]
    (cond
      (schema? value) (schema-display-form value)
      :else (render-type-form (schema->type value)))))

(defn display
  [value]
  (some-> value
          display-form
          pr-str))

(declare type->schema-compat)

(defn fn-method->schema-compat
  [method]
  (mapv (fn [idx input]
          (s/one (type->schema-compat input)
                 (symbol (str "arg" idx))))
        (range)
        (:inputs method)))

(defn type->schema-compat
  [type]
  (let [type (normalize-type type)]
    (cond
      (at/dyn-type? type) s/Any
      (at/bottom-type? type) sb/Bottom
      (at/ground-type? type)
      (let [ground (:ground type)]
        (cond
          (= ground :int) s/Int
          (= ground :str) s/Str
          (= ground :keyword) s/Keyword
          (= ground :symbol) s/Symbol
          (= ground :bool) s/Bool
          (and (map? ground) (:class ground)) (:class ground)
          :else ground))

      (at/refinement-type? type)
      (or (get-in type [:adapter-data :source-schema])
          (type->schema-compat (:base type)))

      (at/adapter-leaf-type? type)
      (or (get-in type [:adapter-data :source-schema])
          s/Any)

      (at/optional-key-type? type)
      (s/optional-key (type->schema-compat (:inner type)))

      (at/value-type? type)
      (let [value (:value type)
            inner (type->schema-compat (:inner type))]
        (if (sb/schema-literal? value)
          value
          (sb/valued-schema inner value)))

      (at/type-var-type? type) type
      (at/forall-type? type) type
      (at/sealed-dyn-type? type) type

      (at/fn-method-type? type)
      (s/make-fn-schema (type->schema-compat (:output type))
                        [(fn-method->schema-compat type)])

      (at/fun-type? type)
      (s/make-fn-schema (type->schema-compat (:output (first (:methods type))))
                        (mapv fn-method->schema-compat (:methods type)))

      (at/maybe-type? type) (s/maybe (type->schema-compat (:inner type)))
      (at/union-type? type) (apply sb/join (map type->schema-compat (:members type)))
      (at/intersection-type? type) (apply s/both (map type->schema-compat (:members type)))
      (at/map-type? type)
      (into {}
            (map (fn [[k v]]
                   [(type->schema-compat k)
                    (type->schema-compat v)]))
            (:entries type))
      (at/vector-type? type) (mapv type->schema-compat (:items type))
      (at/set-type? type) (into #{} (map type->schema-compat) (:members type))
      (at/seq-type? type) (doall (map type->schema-compat (:items type)))
      (at/var-type? type) (sb/variable (type->schema-compat (:inner type)))
      (at/placeholder-type? type) (sb/placeholder-schema (:ref type))
      :else type)))

(defn type-var-name
  [type]
  (when (at/type-var-type? type)
    (:name type)))

(defn type-free-vars
  [type]
  (let [type (schema->type type)]
    (cond
      (or (at/dyn-type? type)
          (at/bottom-type? type)
          (at/ground-type? type)
          (at/refinement-type? type)
          (at/adapter-leaf-type? type)
          (at/optional-key-type? type)
          (at/placeholder-type? type))
      #{}

      (at/fn-method-type? type)
      (into (type-free-vars (:output type))
            (mapcat type-free-vars (:inputs type)))

      (at/fun-type? type)
      (into #{} (mapcat type-free-vars (:methods type)))

      (at/maybe-type? type)
      (type-free-vars (:inner type))

      (at/union-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (at/intersection-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (at/map-type? type)
      (reduce (fn [acc [k v]]
                (into acc (concat (type-free-vars k)
                                  (type-free-vars v))))
              #{}
              (:entries type))

      (at/vector-type? type)
      (into #{} (mapcat type-free-vars (:items type)))

      (at/set-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (at/seq-type? type)
      (into #{} (mapcat type-free-vars (:items type)))

      (at/var-type? type)
      (type-free-vars (:inner type))

      (at/value-type? type)
      (type-free-vars (:inner type))

      (at/type-var-type? type)
      #{(:name type)}

      (at/forall-type? type)
      (disj (type-free-vars (:body type)) (:binder type))

      (at/sealed-dyn-type? type)
      (type-free-vars (:ground type))

      :else
      #{})))

(defn type-substitute
  [type binder replacement]
  (let [type (schema->type type)
        replacement (schema->type replacement)]
    (cond
      (or (at/dyn-type? type)
          (at/bottom-type? type)
          (at/ground-type? type)
          (at/refinement-type? type)
          (at/adapter-leaf-type? type)
          (at/placeholder-type? type))
      type

      (at/optional-key-type? type)
      (at/->OptionalKeyT (type-substitute (:inner type) binder replacement))

      (at/fn-method-type? type)
      (at/->FnMethodT (mapv #(type-substitute % binder replacement) (:inputs type))
                   (type-substitute (:output type) binder replacement)
                   (:min-arity type)
                   (:variadic? type))

      (at/fun-type? type)
      (at/->FunT (mapv #(type-substitute % binder replacement) (:methods type)))

      (at/maybe-type? type)
      (at/->MaybeT (type-substitute (:inner type) binder replacement))

      (at/union-type? type)
      (at/->UnionT (set (map #(type-substitute % binder replacement) (:members type))))

      (at/intersection-type? type)
      (at/->IntersectionT (set (map #(type-substitute % binder replacement) (:members type))))

      (at/map-type? type)
      (at/->MapT (into {}
                     (map (fn [[k v]]
                            [(type-substitute k binder replacement)
                             (type-substitute v binder replacement)]))
                     (:entries type)))

      (at/vector-type? type)
      (at/->VectorT (mapv #(type-substitute % binder replacement) (:items type))
                 (:homogeneous? type))

      (at/set-type? type)
      (at/->SetT (set (map #(type-substitute % binder replacement) (:members type)))
               (:homogeneous? type))

      (at/seq-type? type)
      (at/->SeqT (mapv #(type-substitute % binder replacement) (:items type))
              (:homogeneous? type))

      (at/var-type? type)
      (at/->VarT (type-substitute (:inner type) binder replacement))

      (at/value-type? type)
      (at/->ValueT (type-substitute (:inner type) binder replacement)
                (:value type))

      (at/type-var-type? type)
      (if (= binder (:name type))
        replacement
        type)

      (at/forall-type? type)
      (if (= binder (:binder type))
        type
        (at/->ForallT (:binder type)
                   (type-substitute (:body type) binder replacement)))

      (at/sealed-dyn-type? type)
      (at/->SealedDynT (type-substitute (:ground type) binder replacement))

      :else
      type)))

(def derived-type-keys
  [:node-type])

(defn strip-derived-types
  [entry]
  (cond
    (nil? entry) nil
    (not (map? entry)) entry
    :else
    (let [entry (apply dissoc entry derived-type-keys)]
      (cond-> entry
        (contains? entry :locals) (update :locals (fn [locals]
                                                    (into {}
                                                          (map (fn [[k v]]
                                                                 [k (strip-derived-types v)]))
                                                          locals)))

        (contains? entry :arglists) (update :arglists (fn [arglists]
                                                        (into {}
                                                              (map (fn [[k v]]
                                                                     [k (strip-derived-types v)]))
                                                              arglists)))

        (contains? entry :arg-schema) (update :arg-schema #(mapv strip-derived-types %))
        (contains? entry :params) (update :params #(mapv strip-derived-types %))))))

(defn maybe-schema
  [schema]
  (let [schema (canonicalize-schema schema)]
    (if (sb/maybe? schema)
      schema
      (s/maybe schema))))

(declare semantic-value-schema)

(defn semantic-value-schema
  [schema]
  (let [schema (canonicalize-schema schema)]
    (cond
      (sb/placeholder-schema? schema) schema
      (sb/valued-schema? schema) (semantic-value-schema (:schema schema))
      (sb/maybe? schema) (s/maybe (semantic-value-schema (:schema schema)))
      (sb/join? schema) (schema-join (set (map semantic-value-schema (:schemas schema))))
      (sb/variable? schema) (sb/variable (semantic-value-schema (:schema schema)))
      (sb/plain-map-schema? schema) (into {}
                                     (map (fn [[k v]]
                                            [(semantic-value-schema k)
                                             (semantic-value-schema v)]))
                                     schema)
      (vector? schema) (mapv semantic-value-schema schema)
      (set? schema) (into #{} (map semantic-value-schema) schema)
      (seq? schema) (doall (map semantic-value-schema schema))
      :else schema)))

(defn polarity->side
  [polarity]
  (case polarity
    :positive :term
    :negative :context
    :global :global
    :none :none
    :term))

(defn flip-polarity
  [polarity]
  (case polarity
    :positive :negative
    :negative :positive
    polarity))

(defn union-like-branches
  [schema]
  (let [schema (canonicalize-schema schema)]
    (cond
      (sb/join? schema) (set (:schemas schema))
      (sb/either? schema) (set (:schemas schema))
      (sb/cond-pre? schema) (set (:schemas schema))
      (sb/conditional-schema? schema) (->> (:preds-and-schemas schema)
                                        (map second)
                                        set)
      :else nil)))

(defn union-like-join
  [schema]
  (when-let [branches (union-like-branches schema)]
    (schema-join branches)))

(defn both-components
  [schema]
  (when-let [schema (and (sb/both? schema)
                         (canonicalize-schema schema))]
    (set (:schemas schema))))

(defn unknown-type?
  [type]
  (let [type (schema->type type)]
    (cond
      (at/dyn-type? type) true
      (at/placeholder-type? type) true
      (at/maybe-type? type) (unknown-type? (:inner type))
      (at/union-type? type) (some unknown-type? (:members type))
      :else false)))

(defn unknown-schema?
  [schema]
  (unknown-type? (schema->type schema)))

(declare resolve-placeholders)

(defn resolve-placeholders
  [schema resolve-placeholder]
  (let [schema (canonicalize-schema schema)]
    (cond
      (sb/placeholder-schema? schema)
      (canonicalize-schema (or (resolve-placeholder (sb/placeholder-ref schema))
                               schema))

      (sb/bottom-schema? schema)
      sb/Bottom

      (sb/fn-schema? schema)
      (let [{:keys [input-schemas output-schema]} (into {} schema)]
        (s/make-fn-schema (resolve-placeholders output-schema resolve-placeholder)
                          (mapv (fn [inputs]
                                  (mapv (fn [one]
                                          (let [m (try (into {} one)
                                                       (catch Exception _e nil))]
                                            (if (map? m)
                                              (s/one (resolve-placeholders (:schema m) resolve-placeholder)
                                                     (:name m))
                                              one)))
                                        inputs))
                                input-schemas)))

      (instance? One schema)
      (canonicalize-one (assoc (into {} schema)
                               :schema (resolve-placeholders (:schema schema)
                                                            resolve-placeholder)))

      (sb/maybe? schema)
      (s/maybe (resolve-placeholders (:schema schema) resolve-placeholder))

      (sb/join? schema)
      (schema-join (set (map #(resolve-placeholders % resolve-placeholder)
                             (:schemas schema))))

      (sb/valued-schema? schema)
      (sb/valued-schema (resolve-placeholders (:schema schema) resolve-placeholder)
                     (:value schema))

      (sb/variable? schema)
      (sb/variable (resolve-placeholders (:schema schema) resolve-placeholder))

      (record? schema)
      schema

      (map? schema)
      (into {}
            (map (fn [[k v]]
                   [(resolve-placeholders k resolve-placeholder)
                    (resolve-placeholders v resolve-placeholder)]))
            schema)

      (vector? schema)
      (mapv #(resolve-placeholders % resolve-placeholder) schema)

      (set? schema)
      (into #{} (map #(resolve-placeholders % resolve-placeholder)) schema)

      (seq? schema)
      (doall (map #(resolve-placeholders % resolve-placeholder) schema))

      :else schema)))
