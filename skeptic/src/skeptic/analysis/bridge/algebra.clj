(ns skeptic.analysis.bridge.algebra
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at])
  (:import [schema.core One]))

(defn type-var-name
  [type]
  (when (at/type-var-type? type)
    (:name type)))

(defn type-free-vars
  [type]
  (let [type (ab/schema->type type)]
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
  (let [type (ab/schema->type type)
        replacement (ab/schema->type replacement)]
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

(defn resolve-placeholders
  [schema resolve-placeholder]
  (let [schema (abc/canonicalize-schema schema)]
    (cond
      (sb/placeholder-schema? schema)
      (abc/canonicalize-schema (or (resolve-placeholder (sb/placeholder-ref schema))
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
      (abc/canonicalize-one (assoc (into {} schema)
                               :schema (resolve-placeholders (:schema schema)
                                                            resolve-placeholder)))

      (sb/maybe? schema)
      (s/maybe (resolve-placeholders (:schema schema) resolve-placeholder))

      (sb/join? schema)
      (abc/schema-join (set (map #(resolve-placeholders % resolve-placeholder)
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
