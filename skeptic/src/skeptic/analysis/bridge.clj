(ns skeptic.analysis.bridge
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
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
        (sb/schema-literal? schema) (ato/exact-value-type schema)
        (s/optional-key? schema) (at/->OptionalKeyT (import-schema-type (:k schema)))
        (sb/eq? schema) (ato/exact-value-type (sb/de-eq schema))
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
        (sb/enum-schema? schema) (ato/union-type (map ato/exact-value-type (sb/de-enum schema)))
        (sb/join? schema) (ato/union-type (map import-schema-type (:schemas schema)))
        (sb/either? schema) (ato/union-type (map import-schema-type (:schemas schema)))
        (sb/conditional-schema? schema) (ato/union-type (map (comp import-schema-type second) (:preds-and-schemas schema)))
        (sb/cond-pre? schema) (ato/union-type (map import-schema-type (:schemas schema)))
        (sb/both? schema) (ato/intersection-type (map import-schema-type (:schemas schema)))
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
  [schema]
  (let [schema (abl/localize-schema-value schema)]
    (when-not (abc/schema? schema)
      (throw (IllegalArgumentException.
              (format "Expected Schema-domain value: %s"
                      (pr-str schema)))))
    (import-schema-type schema)))
