(ns skeptic.analysis.bridge
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at])
  (:import [schema.core One]))

(declare schema->type)

(defn broad-dynamic-schema?
  [schema]
  (contains? (set [s/Any
                   s/Num
                   Number
                   java.lang.Number
                   Object
                   java.lang.Object])
             schema))

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
      (at/->GroundT {:class schema} (abc/schema-explain schema))
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
  (let [value (abl/localize-schema-value value)]
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
  (let [value (abl/localize-schema-value value)]
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

(defn- refinement-import-type
  [schema]
  (at/->RefinementT (schema->type (sb/de-constrained schema))
                 (abc/schema-display-form schema)
                 (fn [value]
                   (= (sb/check-if-schema schema value) ::schema-valid))
                 {:adapter :schema
                  :kind :constrained}))

(defn- adapter-leaf-import-type
  [schema]
  (at/->AdapterLeafT :schema
                  (abc/schema-display-form schema)
                  (fn [value]
                    (= (sb/check-if-schema schema value) ::schema-valid))
                  {:source-schema schema}))

(defn import-schema-type
  [schema]
  (let [schema (abl/localize-schema-value schema)]
    (when-not (abc/schema? schema)
      (throw (IllegalArgumentException.
              (format "Expected Schema-domain value: %s" (pr-str schema)))))
    (let [schema (abc/canonicalize-schema schema)]
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
  (let [value (abl/localize-schema-value value)]
    (cond
      (sb/placeholder-schema? value) (import-schema-type value)
      (type-domain-value? value) (normalize-type value)
      (abc/schema? value) (import-schema-type value)
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
