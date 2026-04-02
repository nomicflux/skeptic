(ns skeptic.analysis.schema
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
         render-type
         matches-map)

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
  ;; Nils treated as an automatic `maybe`; this isn't strictly necessary, as `maybe x` is just `nil || x`, but `nil` analysis is
  ;; important enough that they are treated as a separate case
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

(declare type-domain-value?
         normalize-type
         import-schema-type
         schema->type
         check-cast
         type-compatible-map-value?)

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

(declare optional-key-inner
         map-entry-kind
         value-satisfies-type?
         leaf-overlap?)

(defn finite-exact-key-values
  [type]
  (let [type (optional-key-inner (schema->type type))]
    (cond
      (at/value-type? type)
      #{(:value type)}

      (at/union-type? type)
      (let [member-values (map finite-exact-key-values (:members type))]
        (when (every? set? member-values)
          (apply set/union member-values)))

      :else
      nil)))

(def map-key-query-tag
  ::map-key-query)

(defn map-key-query?
  [query]
  (at/tagged-map? query map-key-query-tag true))

(defn exact-key-query
  ([schema value]
   (exact-key-query schema value nil))
  ([schema value source-form]
   {map-key-query-tag true
    :kind :exact
    :schema (canonicalize-schema schema)
    :value value
    :source-form source-form}))

(defn domain-key-query
  ([schema]
   (domain-key-query schema nil))
  ([schema source-form]
   {map-key-query-tag true
    :kind :domain
    :schema (canonicalize-schema schema)
    :source-form source-form}))

(defn exact-key-query?
  [query]
  (and (map-key-query? query)
       (= :exact (:kind query))))

(defn map-key-query
  ([key]
   (map-key-query key nil))
  ([key source-form]
   (let [key (localize-schema-value key)]
     (cond
       (map-key-query? key)
       (update key :schema canonicalize-schema)

       (sb/valued-schema? key)
       (exact-key-query (:schema key) (:value key) (:value key))

       :else
       (let [exact-values (finite-exact-key-values key)]
         (if (and exact-values
                  (= 1 (count exact-values)))
           (exact-key-query key (first exact-values) source-form)
           (domain-key-query key source-form)))))))

(defn query-key-type
  [query]
  (if (exact-key-query? query)
    (exact-value-import-type (:value query))
    (schema->type (:schema query))))

(defn exact-entry-kind
  [key-type]
  (if (at/optional-key-type? key-type)
    :optional-explicit
    :required-explicit))

(defn descriptor-entry
  [entry-key entry-value kind]
  (let [entry-key (canonicalize-schema entry-key)
        entry-value (canonicalize-schema entry-value)
        key-type (schema->type entry-key)
        inner-key-type (optional-key-inner key-type)]
    {:key entry-key
     :value entry-value
     :kind kind
     :key-type key-type
     :inner-key-type inner-key-type
     :exact-value (when (at/value-type? inner-key-type)
                    (:value inner-key-type))}))

(defn add-descriptor-entry
  [descriptor entry]
  (if-let [exact-value (:exact-value entry)]
    (case (:kind entry)
      :required-explicit (assoc-in descriptor [:required-exact exact-value] entry)
      :optional-explicit (assoc-in descriptor [:optional-exact exact-value] entry)
      (update descriptor :schema-entries conj entry))
    (update descriptor :schema-entries conj entry)))

(defn map-entry-descriptor
  [entries]
  (let [entries (canonicalize-schema entries)]
    (reduce (fn [descriptor [entry-key entry-value]]
              (let [entry-key (canonicalize-schema entry-key)
                    entry-value (canonicalize-schema entry-value)
                    key-type (schema->type entry-key)]
                (if-let [exact-values (finite-exact-key-values key-type)]
                  (reduce (fn [desc exact-value]
                            (add-descriptor-entry
                              desc
                              (descriptor-entry (exact-value-import-type exact-value)
                                                entry-value
                                                (exact-entry-kind key-type))))
                          descriptor
                          exact-values)
                  (add-descriptor-entry
                    descriptor
                    (descriptor-entry entry-key
                                      entry-value
                                      (map-entry-kind entries entry-key))))))
            {:entries entries
             :required-exact {}
             :optional-exact {}
             :schema-entries []}
            entries)))

(defn effective-exact-entries
  [descriptor]
  (concat (vals (:required-exact descriptor))
          (->> (:optional-exact descriptor)
               (remove (fn [[value _entry]]
                         (contains? (:required-exact descriptor) value)))
               (map val))))

(defn exact-key-entry
  [descriptor exact-value]
  (or (get-in descriptor [:required-exact exact-value])
      (get-in descriptor [:optional-exact exact-value])))

(declare schema-compatible?
         schema-equivalent?
         valued-compatible?)

(defn key-domain-covered?
  [source-key target-key]
  (let [source-key (optional-key-inner (schema->type source-key))
        target-key (optional-key-inner (schema->type target-key))]
    (cond
      (at/value-type? source-key)
      (value-satisfies-type? (:value source-key) target-key)

      (at/union-type? source-key)
      (every? #(key-domain-covered? % target-key) (:members source-key))

      (at/union-type? target-key)
      (some #(key-domain-covered? source-key %) (:members target-key))

      (at/maybe-type? source-key)
      (key-domain-covered? (:inner source-key) target-key)

      (at/maybe-type? target-key)
      (key-domain-covered? source-key (:inner target-key))

      (at/value-type? target-key)
      false

      :else
      (:ok? (check-cast source-key target-key)))))

(defn key-domain-overlap?
  [source-key target-key]
  (let [source-key (optional-key-inner (schema->type source-key))
        target-key (optional-key-inner (schema->type target-key))]
    (cond
      (or (at/dyn-type? source-key)
          (at/dyn-type? target-key)
          (at/placeholder-type? source-key)
          (at/placeholder-type? target-key))
      true

      (at/value-type? source-key)
      (value-satisfies-type? (:value source-key) target-key)

      (at/value-type? target-key)
      (value-satisfies-type? (:value target-key) source-key)

      (at/union-type? source-key)
      (some #(key-domain-overlap? % target-key) (:members source-key))

      (at/union-type? target-key)
      (some #(key-domain-overlap? source-key %) (:members target-key))

      (at/maybe-type? source-key)
      (key-domain-overlap? (:inner source-key) target-key)

      (at/maybe-type? target-key)
      (key-domain-overlap? source-key (:inner target-key))

      :else
      (or (:ok? (check-cast source-key target-key))
          (:ok? (check-cast target-key source-key))
          (leaf-overlap? source-key target-key)))))

(defn exact-key-candidates
  [descriptor exact-value]
  (if-let [entry (exact-key-entry descriptor exact-value)]
    [entry]
    (->> (:schema-entries descriptor)
         (filter #(value-satisfies-type? exact-value (:inner-key-type %)))
         vec)))

(defn domain-key-candidates
  [descriptor key-type]
  (let [key-type (optional-key-inner (schema->type key-type))]
    (vec
      (concat
        (filter #(value-satisfies-type? (:exact-value %) key-type)
                (effective-exact-entries descriptor))
        (filter #(key-domain-overlap? key-type (:inner-key-type %))
                (:schema-entries descriptor))))))

(defn map-lookup-candidates
  [entries key-query]
  (let [descriptor (map-entry-descriptor entries)
        key-query (map-key-query key-query)]
    (if (exact-key-query? key-query)
      (exact-key-candidates descriptor (:value key-query))
      (domain-key-candidates descriptor (query-key-type key-query)))))

(defn candidate-value-schema
  [candidates]
  (when (seq candidates)
    (schema-join (set (map (comp semantic-value-schema :value) candidates)))))

(defn nested-value-compatible?
  [expected actual]
  (let [actual (canonicalize-schema actual)]
    (if (sb/valued-schema? actual)
      (or (schema-compatible? expected (:value actual))
          (schema-compatible? expected (:schema actual)))
      (schema-compatible? expected actual))))

(def no-default ::no-default)

(defn map-get-schema
  ([m key]
   (map-get-schema m key no-default))
  ([m key default]
   (let [m (canonicalize-schema m)
         key-query (map-key-query key)
         default-provided? (not= default no-default)
         default-schema (when default-provided?
                          (canonicalize-schema default))]
     (cond
       (sb/maybe? m)
       (schema-join
        [(map-get-schema (sb/de-maybe m) key-query default)
         (or default-schema (s/maybe s/Any))])

       (sb/join? m)
       (schema-join (set (map #(map-get-schema % key-query default) (:schemas m))))

       (sb/plain-map-schema? m)
       (if-let [candidates (seq (map-lookup-candidates m key-query))]
         (let [base-value (candidate-value-schema candidates)
               base-value (if (and (exact-key-query? key-query)
                                   (= 1 (count candidates))
                                   (= :optional-explicit (:kind (first candidates)))
                                   (not default-provided?))
                            (maybe-schema base-value)
                            base-value)]
           (if default-provided?
             (schema-join [base-value default-schema])
             base-value))
         (if default-provided?
           default-schema
           s/Any))

       :else
       (if default-provided?
         (schema-join [s/Any default-schema])
         s/Any)))))

(defn candidate-value-type
  [candidates]
  (when (seq candidates)
    (union-type (map :value candidates))))

(defn map-get-type
  ([m key]
   (map-get-type m key no-default))
  ([m key default]
   (let [m (schema->type m)
         key-query (map-key-query key)
         default-provided? (not= default no-default)
         default-type (when default-provided?
                        (schema->type default))]
     (cond
       (at/maybe-type? m)
       (union-type
        [(map-get-type (:inner m) key-query default)
         (or default-type (at/->MaybeT at/Dyn))])

       (at/union-type? m)
       (union-type (map #(map-get-type % key-query default) (:members m)))

       (at/map-type? m)
       (if-let [candidates (seq (map-lookup-candidates (:entries m) key-query))]
         (let [base-value (candidate-value-type candidates)
               base-value (if (and (exact-key-query? key-query)
                                   (= 1 (count candidates))
                                   (= :optional-explicit (:kind (first candidates)))
                                   (not default-provided?))
                            (at/->MaybeT base-value)
                            base-value)]
           (if default-provided?
             (union-type [base-value default-type])
             base-value))
         (if default-provided?
           default-type
           at/Dyn))

       :else
       (if default-provided?
         (union-type [at/Dyn default-type])
         at/Dyn)))))

(defn merge-map-schemas
  [schemas]
  (let [schemas (mapv canonicalize-schema schemas)]
    (if (every? sb/plain-map-schema? schemas)
      (reduce merge {} schemas)
      s/Any)))

(defn merge-map-types
  [types]
  (let [types (mapv schema->type types)]
    (if (every? at/map-type? types)
      (at/->MapT (apply merge (map :entries types)))
      at/Dyn)))

(defn schema-equivalent?
  [expected actual]
  (= (canonicalize-schema expected)
     (canonicalize-schema actual)))

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

(defn ensure-cast-state
  [cast-state]
  (merge {:nu-bindings []
          :abstract-vars #{}
          :active-seals #{}}
         cast-state))

(defn cast-state
  [opts]
  (ensure-cast-state (:cast-state opts)))

(defn with-abstract-var
  [opts binder]
  (assoc opts :cast-state (update (cast-state opts) :abstract-vars conj binder)))

(defn with-nu-binding
  [opts binder witness-type]
  (assoc opts :cast-state (-> (cast-state opts)
                              (update :nu-bindings conj {:type-var binder
                                                         :witness-type (schema->type witness-type)})
                              (update :abstract-vars conj binder))))

(defn register-seal
  [opts sealed-type]
  (assoc opts :cast-state (update (cast-state opts) :active-seals conj (schema->type sealed-type))))

(defn sealed-ground-name
  [type]
  (some-> type schema->type :ground type-var-name))

(defn contains-sealed-ground?
  [type binder]
  (let [type (schema->type type)]
    (cond
      (at/sealed-dyn-type? type)
      (= binder (sealed-ground-name type))

      (at/fn-method-type? type)
      (or (contains-sealed-ground? (:output type) binder)
          (some #(contains-sealed-ground? % binder) (:inputs type)))

      (at/fun-type? type)
      (some #(contains-sealed-ground? % binder) (:methods type))

      (at/maybe-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (or (at/union-type? type)
          (at/intersection-type? type))
      (some #(contains-sealed-ground? % binder) (:members type))

      (at/map-type? type)
      (some (fn [[k v]]
              (or (contains-sealed-ground? k binder)
                  (contains-sealed-ground? v binder)))
            (:entries type))

      (or (at/vector-type? type)
          (at/seq-type? type))
      (some #(contains-sealed-ground? % binder) (:items type))

      (at/set-type? type)
      (some #(contains-sealed-ground? % binder) (:members type))

      (at/var-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (at/value-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (at/forall-type? type)
      (and (not= binder (:binder type))
           (contains-sealed-ground? (:body type) binder))

      :else
      false)))

(defn cast-result
  [{:keys [ok? source-type target-type rule polarity reason children details]}]
  (cond-> {:ok? ok?
           :blame-side (if ok? :none (polarity->side polarity))
           :blame-polarity (if ok? :none polarity)
           :rule rule
           :source-type source-type
           :target-type target-type
           :children (vec children)
           :reason reason}
    (map? details) (merge details)))

(defn cast-ok
  ([source-type target-type rule]
   (cast-ok source-type target-type rule [] nil))
  ([source-type target-type rule children]
   (cast-ok source-type target-type rule children nil))
  ([source-type target-type rule children details]
   (cast-result {:ok? true
                 :source-type source-type
                 :target-type target-type
                 :rule rule
                 :polarity :none
                 :children children
                 :details details})))

(defn cast-fail
  ([source-type target-type rule polarity reason]
   (cast-fail source-type target-type rule polarity reason [] nil))
  ([source-type target-type rule polarity reason children]
   (cast-fail source-type target-type rule polarity reason children nil))
  ([source-type target-type rule polarity reason children details]
   (cast-result {:ok? false
                 :source-type source-type
                 :target-type target-type
                 :rule rule
                 :polarity polarity
                 :reason reason
                 :children children
                 :details details})))

(defn with-cast-path
  [result segment]
  (cond-> result
    (some? segment) (update :path (fnil conj []) segment)))

(defn indexed-cast-children
  [segment-kind build-child xs]
  (mapv (fn [idx x]
          (with-cast-path (build-child x)
            {:kind segment-kind
             :index idx}))
        (range)
        xs))

(defn all-ok?
  [results]
  (every? :ok? results))

(defn check-type-test
  ([value-type ground-type]
   (check-type-test value-type ground-type {}))
  ([value-type ground-type opts]
   (let [value-type (schema->type value-type)
         ground-type (schema->type ground-type)]
     (if (at/sealed-dyn-type? value-type)
       (cast-fail value-type
                  ground-type
                  :is-tamper
                  :global
                  :is-tamper
                  []
                  {:cast-state (cast-state opts)})
       (cast-ok value-type
                ground-type
                :dynamic-test
                []
                {:matches? (= value-type ground-type)
                 :cast-state (cast-state opts)})))))

(defn exit-nu-scope
  ([type binder]
   (exit-nu-scope type binder {}))
  ([type binder opts]
   (let [type (schema->type type)
         binder (or (type-var-name (schema->type binder))
                    binder)]
     (if (contains-sealed-ground? type binder)
       (cast-fail type
                  (at/->TypeVarT binder)
                  :nu-tamper
                  :global
                  :nu-tamper
                  []
                  {:cast-state (cast-state opts)})
       (cast-ok type
                (at/->TypeVarT binder)
                :nu-pass
                []
                {:cast-state (cast-state opts)})))))

(defn method-accepts-arity?
  [method arity]
  (if (:variadic? method)
    (>= arity (:min-arity method))
    (= arity (:min-arity method))))

(defn matching-source-method
  [source-fun target-method]
  (some #(when (method-accepts-arity? % (count (:inputs target-method)))
           %)
        (:methods source-fun)))

(defn optional-key-inner
  [type]
  (if (at/optional-key-type? type)
    (:inner type)
    type))

(defn exact-value-type?
  [type]
  (at/value-type? (schema->type type)))

(defn map-entry-kind
  ([entry-key]
   (let [entry-key (canonicalize-schema entry-key)]
     (cond
       (and (not (at/semantic-type-value? entry-key))
            (s/optional-key? entry-key))
       :optional-explicit

       (and (not (at/semantic-type-value? entry-key))
            (s/specific-key? entry-key))
       :required-explicit

       :else
       (let [entry-type (schema->type entry-key)
             inner (optional-key-inner entry-type)]
         (cond
           (and (at/optional-key-type? entry-type)
                (exact-value-type? inner))
           :optional-explicit

           (exact-value-type? inner)
           :required-explicit

           (and (at/optional-key-type? entry-type)
                (finite-exact-key-values inner))
           :optional-explicit

           (finite-exact-key-values inner)
           :required-explicit

           :else
           :extra-schema)))))
  ([entries entry-key]
   (let [entries (canonicalize-schema entries)
         entry-key (canonicalize-schema entry-key)
         typed-entries? (every? at/semantic-type-value? (keys entries))]
     (if (and (sb/plain-map-schema? entries)
              (not typed-entries?))
       (let [extra-key (s/find-extra-keys-schema entries)]
         (if (= entry-key extra-key)
           :extra-schema
           (map-entry-kind entry-key)))
       (map-entry-kind entry-key)))))

(defn path-key
  [type]
  (let [type (optional-key-inner type)]
    (when (exact-value-type? type)
      (:value type))))

(defn with-map-path
  [cast-result key]
  (if-let [path-value (path-key key)]
    (with-cast-path cast-result
      {:kind :map-key
       :key path-value})
    cast-result))

(defn map-contains-key-classification
  [type key]
  (let [descriptor (map-entry-descriptor (:entries (schema->type type)))
        exact-entry (exact-key-entry descriptor key)]
    (if exact-entry
      (if (= :required-explicit (:kind exact-entry))
        :always
        :unknown)
      (if (seq (exact-key-candidates descriptor key))
        :unknown
        :never))))

(defn contains-key-classification
  [schema key]
  (let [type (schema->type schema)]
    (cond
      (at/bottom-type? type) :never

      (at/maybe-type? type)
      (case (contains-key-classification (:inner type) key)
        :never :never
        :always :unknown
        :unknown :unknown)

      (at/map-type? type)
      (map-contains-key-classification type key)

      :else
      :unknown)))

(defn contains-key-type-classification
  [type key]
  (let [type (schema->type type)]
    (if (at/union-type? type)
      (let [classifications (set (map #(contains-key-type-classification % key)
                                      (:members type)))]
        (cond
          (= #{:always} classifications) :always
          (= #{:never} classifications) :never
          :else :unknown))
      (contains-key-classification type key))))

(defn refine-schema-by-contains-key
  [schema key polarity]
  (let [schema (canonicalize-schema schema)
        branches (or (union-like-branches schema) #{schema})
        kept (->> branches
                  (keep (fn [branch]
                          (let [classification (contains-key-classification branch key)]
                            (case [polarity classification]
                              [true :never] nil
                              [false :always] nil
                              branch))))
                  set)]
    (cond
      (empty? kept) sb/Bottom
      (= 1 (count kept)) (first kept)
      :else (schema-join kept))))

(defn refine-type-by-contains-key
  [type key polarity]
  (let [type (schema->type type)
        branches (if (at/union-type? type)
                   (:members type)
                   #{type})
        kept (->> branches
                  (keep (fn [branch]
                          (let [classification (contains-key-type-classification branch key)]
                            (case [polarity classification]
                              [true :never] nil
                              [false :always] nil
                              branch))))
                  set)]
    (cond
      (empty? kept) at/BottomType
      (= 1 (count kept)) (first kept)
      :else (union-type kept))))

(defn ground-accepts-value?
  [type value]
  (let [ground (:ground (schema->type type))]
    (cond
      (= ground :int) (integer? value)
      (= ground :str) (string? value)
      (= ground :keyword) (keyword? value)
      (= ground :symbol) (symbol? value)
      (= ground :bool) (boolean? value)
      (and (map? ground) (:class ground)) (instance? (:class ground) value)
      :else false)))

(declare value-satisfies-type?)

(defn leaf-overlap?
  [source-type target-type]
  (let [source-type (schema->type source-type)
        target-type (schema->type target-type)]
    (cond
      (at/ground-type? source-type)
      (cond
        (at/ground-type? target-type)
        (let [s (:ground source-type)
              t (:ground target-type)]
          (cond
            (= s t) true
            (and (map? s) (:class s) (map? t) (:class t))
            (or (.isAssignableFrom ^Class (:class s) ^Class (:class t))
                (.isAssignableFrom ^Class (:class t) ^Class (:class s)))
            :else false))

        (at/refinement-type? target-type)
        (leaf-overlap? source-type (:base target-type))

        (at/adapter-leaf-type? target-type)
        true

        :else false)

      (at/refinement-type? source-type)
      (leaf-overlap? (:base source-type) target-type)

      (at/adapter-leaf-type? source-type)
      true

      :else false)))

(defn type-compatible-map-value?
  [value-type expected-type]
  (:ok? (check-cast value-type expected-type)))

(defn set-value-satisfies-type?
  [value members]
  (and (set? value)
       (= (count value) (count members))
       (every? (fn [member-value]
                 (some #(value-satisfies-type? member-value %) members))
               value)))

(defn map-value-satisfies-type?
  [value map-type]
  (and (map? value)
       (let [descriptor (map-entry-descriptor (:entries (schema->type map-type)))
             required-missing (atom (set (keys (:required-exact descriptor))))]
         (and
          (every? (fn [[k v]]
                    (if-let [exact-entry (exact-key-entry descriptor k)]
                      (do
                        (swap! required-missing disj k)
                        (value-satisfies-type? v (:value exact-entry)))
                      (let [candidates (exact-key-candidates descriptor k)]
                        (and (seq candidates)
                             (some #(value-satisfies-type? v (:value %))
                                   candidates)))))
                  value)
          (empty? @required-missing)))))

(defn value-satisfies-type?
  [value type]
  (let [type (schema->type type)]
    (cond
      (or (at/dyn-type? type)
          (at/placeholder-type? type))
      true

      (at/bottom-type? type)
      true

      (at/value-type? type)
      (= value (:value type))

      (at/ground-type? type)
      (ground-accepts-value? type value)

      (at/refinement-type? type)
      (and (value-satisfies-type? value (:base type))
           ((:accepts? type) value))

      (at/adapter-leaf-type? type)
      ((:accepts? type) value)

      (at/optional-key-type? type)
      (value-satisfies-type? value (:inner type))

      (at/maybe-type? type)
      (or (nil? value)
          (value-satisfies-type? value (:inner type)))

      (at/union-type? type)
      (some #(value-satisfies-type? value %) (:members type))

      (at/intersection-type? type)
      (every? #(value-satisfies-type? value %) (:members type))

      (at/map-type? type)
      (map-value-satisfies-type? value type)

      (at/vector-type? type)
      (and (vector? value)
           (if (:homogeneous? type)
             (every? #(value-satisfies-type? % (or (first (:items type)) at/Dyn))
                     value)
             (and (= (count value) (count (:items type)))
                  (every? true? (map value-satisfies-type? value (:items type))))))

      (at/seq-type? type)
      (and (sequential? value)
           (= (count value) (count (:items type)))
           (every? true? (map value-satisfies-type? value (:items type))))

      (at/set-type? type)
      (set-value-satisfies-type? value (:members type))

      (at/var-type? type)
      (and (var? value)
           (value-satisfies-type? @value (:inner type)))

      :else false)))

(declare check-cast)

(defn candidate-value-cast-results
  [source-value target-entries path-key opts]
  (let [results (mapv (fn [target-entry]
                        (with-map-path
                          (check-cast source-value (:value target-entry) opts)
                          path-key))
                      target-entries)]
    (if-let [success (some #(when (:ok? %) %) results)]
      [success]
      results)))

(defn exact-target-entry-cast-results
  [source-type target-type source-descriptor target-entry opts]
  (let [exact-value (:exact-value target-entry)
        source-candidates (exact-key-candidates source-descriptor exact-value)
        source-exact-entry (exact-key-entry source-descriptor exact-value)
        value-results (mapv (fn [source-entry]
                              (with-map-path
                                (check-cast (:value source-entry) (:value target-entry) opts)
                                (:key target-entry)))
                            source-candidates)
        nullable-result (when (and (= :required-explicit (:kind target-entry))
                                   (= :optional-explicit (:kind source-exact-entry)))
                          (with-map-path
                            (cast-fail source-type
                                       target-type
                                       :map-nullable-key
                                       (:polarity opts)
                                       :nullable-key
                                       []
                                       {:actual-key (:key source-exact-entry)
                                        :expected-key (:key target-entry)})
                            (:key target-entry)))]
    (cond
      (empty? source-candidates)
      (if (= :required-explicit (:kind target-entry))
        [(with-map-path
           (cast-fail source-type
                      target-type
                      :map-missing-key
                      (:polarity opts)
                      :missing-key
                      []
                      {:expected-key (:key target-entry)})
           (:key target-entry))]
        [])

      :else
      (cond-> value-results
        nullable-result (conj nullable-result)))))

(defn exact-source-entry-cast-results
  [source-type target-type source-entry target-schema-entries opts]
  (let [target-candidates (->> target-schema-entries
                               (filter #(key-domain-covered? (exact-value-import-type (:exact-value source-entry))
                                                             (:inner-key-type %)))
                               vec)]
    (cond
      (empty? target-candidates)
      [(with-map-path
         (cast-fail source-type
                    target-type
                    :map-unexpected-key
                    (:polarity opts)
                    :unexpected-key
                    []
                    {:actual-key (:key source-entry)})
         (:key source-entry))]

      :else
      (candidate-value-cast-results (:value source-entry)
                                    target-candidates
                                    nil
                                    opts))))

(defn schema-domain-entry-cast-results
  [source-type target-type source-entry target-schema-entries opts]
  (let [source-key-type (:inner-key-type source-entry)]
    (cond
      (at/union-type? source-key-type)
      (mapcat (fn [member]
                (schema-domain-entry-cast-results source-type
                                                  target-type
                                                  (assoc source-entry
                                                         :key member
                                                         :key-type member
                                                         :inner-key-type member
                                                         :exact-value nil)
                                                  target-schema-entries
                                                  opts))
              (:members source-key-type))

      :else
      (let [target-candidates (->> target-schema-entries
                                   (filter #(key-domain-covered? source-key-type
                                                                 (:inner-key-type %)))
                                   vec)]
        (cond
          (empty? target-candidates)
          [(cast-fail source-type
                      target-type
                      :map-key-domain
                      (:polarity opts)
                      :map-key-domain-not-covered
                      []
                      {:actual-key (:key source-entry)
                       :source-key-domain source-key-type})]

          :else
          (candidate-value-cast-results (:value source-entry)
                                        target-candidates
                                        nil
                                        opts))))))

(defn map-cast-children
  [source-type target-type opts]
  (let [source-descriptor (map-entry-descriptor (:entries (schema->type source-type)))
        target-descriptor (map-entry-descriptor (:entries (schema->type target-type)))
        target-exact-entries (vec (effective-exact-entries target-descriptor))
        target-exact-values (set (map :exact-value target-exact-entries))
        target-schema-entries (vec (:schema-entries target-descriptor))
        source-exact-entries (vec (effective-exact-entries source-descriptor))
        source-extra-exact-entries (->> source-exact-entries
                                        (remove #(contains? target-exact-values
                                                            (:exact-value %)))
                                        vec)
        source-schema-entries (vec (:schema-entries source-descriptor))]
    (vec
      (concat
        (mapcat #(exact-target-entry-cast-results source-type
                                                  target-type
                                                  source-descriptor
                                                  %
                                                  opts)
                target-exact-entries)
        (mapcat #(exact-source-entry-cast-results source-type
                                                  target-type
                                                  %
                                                  target-schema-entries
                                                  opts)
                source-extra-exact-entries)
        (mapcat #(schema-domain-entry-cast-results source-type
                                                   target-type
                                                   %
                                                   target-schema-entries
                                                   opts)
                source-schema-entries)))))

(defn collection-cast-children
  [segment-kind source-items target-items opts]
  (mapv (fn [idx source-item target-item]
          (with-cast-path (check-cast source-item target-item opts)
            {:kind segment-kind
             :index idx}))
        (range)
        source-items
        target-items))

(defn expand-vector-items
  [type slot-count]
  (let [items (:items type)]
    (if (:homogeneous? type)
      (vec (repeat slot-count (or (first items) at/Dyn)))
      items)))

(defn vector-cast-slot-count
  [source-type target-type]
  (let [source-count (count (:items source-type))
        target-count (count (:items target-type))
        source-homogeneous? (:homogeneous? source-type)
        target-homogeneous? (:homogeneous? target-type)]
    (cond
      (and source-homogeneous? target-homogeneous?) 1
      target-homogeneous? source-count
      source-homogeneous? target-count
      (= source-count target-count) source-count
      :else nil)))

(defn set-cast-children
  [source-members target-members opts]
  (reduce (fn [acc source-member]
            (if-let [match (some (fn [target-member]
                                   (let [result (check-cast source-member target-member opts)]
                                     (when (:ok? result)
                                       result)))
                                 target-members)]
              (conj acc match)
              (conj acc
                    (with-cast-path
                      (cast-fail source-member
                                 (or (first target-members) at/Dyn)
                                 :set-element
                                 (:polarity opts)
                                 :element-mismatch)
                      {:kind :set-member
                       :member source-member}))))
          []
          source-members))

(defn check-cast
  ([source-type target-type]
   (check-cast source-type target-type {}))
  ([source-type target-type {:keys [polarity] :or {polarity :positive} :as opts}]
   (let [source-type (schema->type source-type)
         target-type (schema->type target-type)
         opts (assoc opts :polarity polarity)]
     (cond
       (at/bottom-type? source-type)
       (cast-ok source-type target-type :bottom-source)

       (= source-type target-type)
       (cast-ok source-type target-type :exact)

       (at/forall-type? target-type)
       (if (contains? (type-free-vars source-type) (:binder target-type))
         (cast-fail source-type
                    target-type
                    :generalize
                    polarity
                    :forall-capture
                    []
                    {:binder (:binder target-type)
                     :cast-state (cast-state opts)})
         (let [child (check-cast source-type
                                 (:body target-type)
                                 (with-abstract-var opts (:binder target-type)))]
           (if (:ok? child)
             (cast-ok source-type
                      target-type
                      :generalize
                      [child]
                      {:binder (:binder target-type)
                       :cast-state (cast-state opts)})
             (cast-fail source-type
                        target-type
                        :generalize
                        polarity
                        :generalize-failed
                        [child]
                        {:binder (:binder target-type)
                         :cast-state (cast-state opts)}))))

       (at/forall-type? source-type)
       (let [instantiated (type-substitute (:body source-type)
                                           (:binder source-type)
                                           at/Dyn)
             child (check-cast instantiated target-type
                               (with-nu-binding opts (:binder source-type) at/Dyn))]
         (if (:ok? child)
           (cast-ok source-type
                    target-type
                    :instantiate
                    [child]
                    {:binder (:binder source-type)
                     :instantiated-type instantiated
                     :cast-state (cast-state opts)})
           (cast-fail source-type
                      target-type
                      :instantiate
                      polarity
                      :instantiate-failed
                      [child]
                      {:binder (:binder source-type)
                       :instantiated-type instantiated
                       :cast-state (cast-state opts)})))

       (and (at/type-var-type? source-type)
            (at/dyn-type? target-type))
       (let [sealed-type (at/->SealedDynT source-type)]
         (cast-ok source-type
                  target-type
                  :seal
                  []
                  {:sealed-type sealed-type
                   :cast-state (:cast-state (register-seal opts sealed-type))}))

       (at/dyn-type? target-type)
       (cast-ok source-type target-type :target-dyn)

       (at/union-type? target-type)
       (let [children (indexed-cast-children :target-union-branch
                                             #(check-cast source-type % opts)
                                             (:members target-type))]
         (if-let [success (some #(when (:ok? %) %) children)]
           (cast-ok source-type target-type :target-union children {:chosen-rule (:rule success)})
           (cast-fail source-type target-type :target-union polarity :no-union-branch children)))

       (at/union-type? source-type)
       (let [children (indexed-cast-children :source-union-branch
                                             #(check-cast % target-type opts)
                                             (:members source-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :source-union children)
           (cast-fail source-type target-type :source-union polarity :source-branch-failed children)))

       (at/intersection-type? target-type)
       (let [children (indexed-cast-children :target-intersection-branch
                                             #(check-cast source-type % opts)
                                             (:members target-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :target-intersection children)
           (cast-fail source-type target-type :target-intersection polarity :target-component-failed children)))

       (at/intersection-type? source-type)
       (let [children (indexed-cast-children :source-intersection-branch
                                             #(check-cast % target-type opts)
                                             (:members source-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :source-intersection children)
           (cast-fail source-type target-type :source-intersection polarity :source-component-failed children)))

       (at/value-type? source-type)
       (if (value-satisfies-type? (:value source-type) target-type)
           (cast-ok source-type target-type :value-exact)
           (cast-fail source-type target-type :value-exact polarity :exact-value-mismatch))

       (at/value-type? target-type)
       (if (value-satisfies-type? (:value target-type) source-type)
           (cast-ok source-type target-type :target-value)
           (cast-fail source-type target-type :target-value polarity :target-value-mismatch))

       (and (at/maybe-type? source-type) (at/maybe-type? target-type))
       (let [child (with-cast-path (check-cast (:inner source-type) (:inner target-type) opts)
                     {:kind :maybe-value})]
         (if (:ok? child)
           (cast-ok source-type target-type :maybe-both [child])
           (cast-fail source-type target-type :maybe-both polarity :maybe-inner-failed [child])))

       (at/maybe-type? target-type)
       (let [child (with-cast-path (check-cast source-type (:inner target-type) opts)
                     {:kind :maybe-value})]
         (if (:ok? child)
           (cast-ok source-type target-type :maybe-target [child])
           (cast-fail source-type target-type :maybe-target polarity :maybe-target-inner-failed [child])))

       (at/maybe-type? source-type)
       (cast-fail source-type target-type :maybe-source polarity :nullable-source)

       (at/optional-key-type? source-type)
       (check-cast (:inner source-type) target-type opts)

       (at/optional-key-type? target-type)
       (check-cast source-type (:inner target-type) opts)

       (at/var-type? source-type)
       (check-cast (:inner source-type) target-type opts)

       (at/var-type? target-type)
       (check-cast source-type (:inner target-type) opts)

       (at/type-var-type? target-type)
       (cond
         (at/sealed-dyn-type? source-type)
         (if (= (sealed-ground-name source-type) (type-var-name target-type))
           (cast-ok source-type
                    target-type
                    :sealed-collapse
                    []
                    {:cast-state (cast-state opts)})
           (cast-fail source-type
                      target-type
                      :sealed-collapse
                      polarity
                      :sealed-ground-mismatch
                      []
                      {:cast-state (cast-state opts)}))

         (or (at/dyn-type? source-type)
             (at/placeholder-type? source-type))
         (cast-ok source-type
                  target-type
                  :type-var-target
                  []
                  {:cast-state (cast-state opts)})

         :else
         (cast-fail source-type
                    target-type
                    :type-var-target
                    polarity
                    :abstract-target-mismatch
                    []
                    {:cast-state (cast-state opts)}))

       (at/type-var-type? source-type)
       (cast-fail source-type
                  target-type
                  :type-var-source
                  polarity
                  :abstract-source-mismatch
                  []
                  {:cast-state (cast-state opts)})

       (at/sealed-dyn-type? source-type)
       (cast-fail source-type
                  target-type
                  :sealed-conflict
                  polarity
                  :sealed-mismatch
                  []
                  {:cast-state (cast-state opts)})

       (and (at/fun-type? source-type) (at/fun-type? target-type))
       (let [children (mapv (fn [target-method]
                              (if-let [source-method (matching-source-method source-type target-method)]
                                (let [domain-results (mapv (fn [idx target-input source-input]
                                                             (with-cast-path
                                                               (check-cast target-input
                                                                           source-input
                                                                           (update opts :polarity flip-polarity))
                                                               {:kind :function-domain
                                                                :index idx}))
                                                           (range)
                                                           (:inputs target-method)
                                                           (:inputs source-method))
                                      range-result (with-cast-path
                                                     (check-cast (:output source-method)
                                                                 (:output target-method)
                                                                 opts)
                                                     {:kind :function-range})
                                      method-children (conj domain-results range-result)]
                                  (if (all-ok? method-children)
                                    (cast-ok source-method target-method :function-method method-children)
                                    (cast-fail source-method target-method :function-method polarity :function-component-failed method-children)))
                                (cast-fail source-type
                                           target-type
                                           :function-arity
                                           polarity
                                           :arity-mismatch
                                           []
                                           {:target-method target-method})))
                            (:methods target-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :function children)
           (cast-fail source-type target-type :function polarity :function-cast-failed children)))

       (and (at/map-type? source-type) (at/map-type? target-type))
       (let [children (map-cast-children source-type target-type opts)]
         (if (all-ok? children)
           (cast-ok source-type target-type :map children)
           (cast-fail source-type target-type :map polarity :map-cast-failed children)))

       (and (at/vector-type? source-type) (at/vector-type? target-type))
       (if-let [slot-count (vector-cast-slot-count source-type target-type)]
         (let [source-items (expand-vector-items source-type slot-count)
               target-items (expand-vector-items target-type slot-count)
               children (collection-cast-children :vector-index source-items target-items opts)]
           (if (all-ok? children)
             (cast-ok source-type target-type :vector children)
             (cast-fail source-type target-type :vector polarity :vector-element-failed children)))
         (cast-fail source-type target-type :vector polarity :vector-arity-mismatch))

       (and (at/seq-type? source-type) (at/seq-type? target-type))
       (let [source-items (:items source-type)
             target-items (:items target-type)]
         (if (= (count source-items) (count target-items))
           (let [children (collection-cast-children :seq-index source-items target-items opts)]
             (if (all-ok? children)
               (cast-ok source-type target-type :seq children)
               (cast-fail source-type target-type :seq polarity :seq-element-failed children)))
           (cast-fail source-type target-type :seq polarity :seq-arity-mismatch)))

       (and (at/set-type? source-type) (at/set-type? target-type))
       (let [source-members (:members source-type)
             target-members (:members target-type)]
         (if (= (count source-members) (count target-members))
           (let [children (set-cast-children source-members target-members opts)]
             (if (all-ok? children)
               (cast-ok source-type target-type :set children)
               (cast-fail source-type target-type :set polarity :set-element-failed children)))
           (cast-fail source-type target-type :set polarity :set-cardinality-mismatch)))

       (or (at/dyn-type? source-type)
           (at/placeholder-type? source-type))
       (cast-ok source-type target-type :residual-dynamic)

       (or (at/ground-type? source-type)
           (at/refinement-type? source-type)
           (at/adapter-leaf-type? source-type))
       (if (leaf-overlap? source-type target-type)
         (cast-ok source-type target-type :leaf-overlap)
         (cast-fail source-type target-type :leaf-overlap polarity :leaf-mismatch))

       :else
       (cast-fail source-type target-type :mismatch polarity :mismatch)))))

(defn valued-compatible?
  [expected actual]
  (let [expected (canonicalize-schema expected)
        actual (canonicalize-schema actual)]
    (cond
      (sb/valued-schema? expected)
      (throw (IllegalArgumentException. "Only actual can be a valued schema"))

      (sb/valued-schema? actual)
      (let [v (:value actual)
            s (:schema actual)
            e (sb/de-maybe expected)]
        (or (schema-equivalent? e v)
            (schema-equivalent? e s)
            (schema-equivalent? e (s/optional-key v))
            (schema-equivalent? e (s/optional-key s))
            (= (sb/check-if-schema e v) ::schema-valid)))

      (or (schema-equivalent? expected actual)
          (schema-equivalent? expected (s/optional-key actual))
          (= (sb/check-if-schema expected actual) ::schema-valid))
      true

      (and (map? expected) (map? actual))
      (every? (fn [[k v]] (matches-map expected k v)) actual)

      :else false)))

(defn get-by-matching-schema
  [m k]
  (candidate-value-schema (map-lookup-candidates m (map-key-query k))))

(defn valued-get
  [m k]
  (get-by-matching-schema m k))

(declare matches-map)

(defn matches-map
  [expected actual-k actual-v]
  (let [expected (canonicalize-schema expected)
        actual-v (canonicalize-schema actual-v)
        descriptor (map-entry-descriptor expected)
        key-query (map-key-query actual-k)]
    (if (exact-key-query? key-query)
      (every? (fn [exact-value]
                (some #(nested-value-compatible? (:value %) actual-v)
                      (exact-key-candidates descriptor exact-value)))
              [(:value key-query)])
      (some #(nested-value-compatible? (:value %) actual-v)
            (filter (fn [entry]
                      (key-domain-covered? (query-key-type key-query)
                                           (:inner-key-type entry)))
                    (:schema-entries descriptor))))))

(defn required-key?
  [k]
  (= :required-explicit (map-entry-kind k)))

(defn schema-compatible?
  [expected actual]
  (:ok? (check-cast (schema->type actual) (schema->type expected))))

(defn schema-values
  [s]
  (cond
    (sb/valued-schema? s) [(:schema s) (:value s)]
    (and (map? s)
         (not (s/optional-key? s)))
    (let [{valued-schemas true base-schemas false} (->> s keys (group-by sb/valued-schema?))

          complex-keys (->> valued-schemas
                            (map (fn [k] (let [v (get s k)]
                                      (map (fn [k2]
                                             {k2 (if (and (schema? k2) (sb/valued-schema? v))
                                                   (:schema v)
                                                   v)})
                                           (schema-values k)))))
                            sb/all-pairs
                            (map (partial into {})))

          complex-values (->> base-schemas
                              (map (fn [k]
                                     (let [v (get s k)]
                                       (map (fn [v2] {k v2})
                                            (if (sb/valued-schema? v) (schema-values v) [v])))))
                              sb/all-pairs
                              (map (partial into {})))

          split-keys (mapcat (fn [vs] (mapv #(merge vs %) complex-keys)) complex-values)]
      split-keys)
    :else [s]))

(s/defschema WithPlaceholder
  {s/Keyword s/Any})

(s/defschema ArgCount
  (s/cond-pre s/Int (s/eq :varargs)))

;; TODO: make these all ns-specific so there are no collisions

(s/defschema AnnotatedExpression
  {:expr s/Any
   :idx s/Int

   (s/optional-key :resolution-path) [s/Any]
   (s/optional-key :schema) s/Any
   (s/optional-key :name) s/Symbol
   (s/optional-key :path) [s/Symbol]
   (s/optional-key :fn-position?) s/Bool
   (s/optional-key :local-vars) {s/Symbol s/Any}
   (s/optional-key :args) [s/Int]
   (s/optional-key :dep-callback) (s/=> (s/recursive #'AnnotatedExpression)
                                        {s/Int (s/recursive #'AnnotatedExpression)} (s/recursive #'AnnotatedExpression))
   (s/optional-key :expected-arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :actual-arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :output) s/Any
   (s/optional-key :arglists) {ArgCount s/Any}
   (s/optional-key :arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :map?) s/Bool
   (s/optional-key :finished?) s/Bool})
