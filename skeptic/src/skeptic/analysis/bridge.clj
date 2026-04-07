(ns skeptic.analysis.bridge
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at])
  (:import [clojure.lang IPersistentCollection]
           [schema.core One]))

(declare schema->type)

(defn broad-dynamic-schema?
  [schema]
  (contains? (set [s/Any
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
           (.isAssignableFrom IPersistentCollection ^Class schema))
      nil
      (and (class? schema)
           (not (broad-dynamic-schema? schema)))
      (at/->GroundT {:class schema} (abc/schema-explain schema))
      :else nil)))

(defn- refinement-import-type
  [schema]
  (at/->RefinementT (schema->type (sb/de-constrained schema))
                 (abc/schema-display-form schema)
                 (fn [value]
                   (= (sb/check-if-schema schema value) sb/plumatic-valid))
                 {:adapter :schema
                  :kind :constrained}))

(defn- adapter-leaf-import-type
  [schema]
  (at/->AdapterLeafT :schema
                  (abc/schema-display-form schema)
                  (fn [value]
                    (= (sb/check-if-schema schema value) sb/plumatic-valid))
                  {:source-schema schema}))

(defn import-schema-type
  "Input must already be in the schema domain (e.g. after declaration admission).
  Localize + canonicalize are applied here for Var resolution and shape normalization."
  [schema]
  (let [schema (abl/localize-value schema)
        schema (abc/canonicalize-schema schema)]
    (cond
      (nil? schema) (at/->MaybeT at/Dyn)
      (= schema sb/Bottom) at/BottomType
      (sb/placeholder-schema? schema) (at/->PlaceholderT (sb/placeholder-ref schema))
      (or (= schema s/Num)
          (and (class? schema) (= schema java.lang.Number)))
      (at/->GroundT {:class java.lang.Number} 'Number)
      (broad-dynamic-schema? schema) at/Dyn
      (instance? One schema) (import-schema-type (:schema (into {} schema)))
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
                                                    (import-schema-type
                                                     (cond
                                                       (instance? One one)
                                                       (:schema (into {} one))

                                                       (and (map? one) (contains? one :schema))
                                                       (:schema one)

                                                       :else
                                                       one)))
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
      (adapter-leaf-import-type schema))))

(defn schema->type
  "Input must be schema-domain (e.g. from admitted declarations)."
  [schema]
  (import-schema-type (abl/localize-value schema)))
