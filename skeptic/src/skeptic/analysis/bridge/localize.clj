(ns skeptic.analysis.bridge.localize
  (:require [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

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

(declare localize-value*)

(defn- localize-unbound-var
  [value seen-vars]
  (localize-value* (at/read-instance-field value "v") seen-vars))

(defn- localize-var
  [value seen-vars]
  (let [var-ref (or (sb/qualified-var-symbol value) value)]
    (cond
      (contains? seen-vars var-ref) (sb/placeholder-schema var-ref)
      (bound? value) (localize-value* @value (conj seen-vars var-ref))
      :else (sb/placeholder-schema var-ref))))

(defn- localize-schema-base-value
  [value seen-vars]
  (cond
    (sb/bottom-schema? value) sb/Bottom
    (sb/join? value)
    (apply sb/join (map #(localize-value* % seen-vars) (:schemas value)))
    (sb/valued-schema? value)
    (sb/valued-schema (localize-value* (:schema value) seen-vars)
                      (localize-value* (:value value) seen-vars))
    (sb/variable? value)
    (sb/variable (localize-value* (:schema value) seen-vars))
    :else value))

(defn- localize-semantic-type
  [value seen-vars]
  (case (at/semantic-type-tag value)
    :skeptic.analysis.types/dyn-type at/Dyn
    :skeptic.analysis.types/bottom-type at/BottomType
    :skeptic.analysis.types/ground-type
    (at/->GroundT (:ground value) (:display-form value))
    :skeptic.analysis.types/refinement-type
    (at/->RefinementT (localize-value* (:base value) seen-vars)
                      (:display-form value)
                      (:accepts? value)
                      (localize-value* (:adapter-data value) seen-vars))
    :skeptic.analysis.types/adapter-leaf-type
    (at/->AdapterLeafT (:adapter value)
                       (:display-form value)
                       (:accepts? value)
                       (localize-value* (:adapter-data value) seen-vars))
    :skeptic.analysis.types/optional-key-type
    (at/->OptionalKeyT (localize-value* (:inner value) seen-vars))
    :skeptic.analysis.types/fn-method-type
    (at/->FnMethodT (localize-value* (:inputs value) seen-vars)
                    (localize-value* (:output value) seen-vars)
                    (:min-arity value)
                    (:variadic? value))
    :skeptic.analysis.types/fun-type
    (at/->FunT (localize-value* (:methods value) seen-vars))
    :skeptic.analysis.types/maybe-type
    (at/->MaybeT (localize-value* (:inner value) seen-vars))
    :skeptic.analysis.types/union-type
    (at/->UnionT (localize-value* (:members value) seen-vars))
    :skeptic.analysis.types/intersection-type
    (at/->IntersectionT (localize-value* (:members value) seen-vars))
    :skeptic.analysis.types/map-type
    (at/->MapT (localize-value* (:entries value) seen-vars))
    :skeptic.analysis.types/vector-type
    (at/->VectorT (localize-value* (:items value) seen-vars)
                  (:homogeneous? value))
    :skeptic.analysis.types/set-type
    (at/->SetT (localize-value* (:members value) seen-vars)
               (:homogeneous? value))
    :skeptic.analysis.types/seq-type
    (at/->SeqT (localize-value* (:items value) seen-vars)
               (:homogeneous? value))
    :skeptic.analysis.types/var-type
    (at/->VarT (localize-value* (:inner value) seen-vars))
    :skeptic.analysis.types/placeholder-type
    (at/->PlaceholderT (localize-value* (:ref value) seen-vars))
    :skeptic.analysis.types/value-type
    (at/->ValueT (localize-value* (:inner value) seen-vars)
                 (localize-value* (:value value) seen-vars))
    :skeptic.analysis.types/type-var-type
    (at/->TypeVarT (:name value))
    :skeptic.analysis.types/forall-type
    (at/->ForallT (:binder value)
                  (localize-value* (:body value) seen-vars))
    :skeptic.analysis.types/sealed-dyn-type
    (at/->SealedDynT (localize-value* (:ground value) seen-vars))
    value))

(defn- localize-raw-collection
  [value seen-vars]
  (cond
    (vector? value) (mapv #(localize-value* % seen-vars) value)
    (set? value) (into #{} (map #(localize-value* % seen-vars)) value)
    (and (map? value) (not (record? value)))
    (into {}
          (map (fn [[k v]]
                 [(localize-value* k seen-vars)
                  (localize-value* v seen-vars)]))
          value)
    (seq? value) (doall (map #(localize-value* % seen-vars) value))
    :else value))

(defn localize-value*
  [value seen-vars]
  (cond
    (nil? value) nil
    (at/same-class-name? value "clojure.lang.Var$Unbound")
    (localize-unbound-var value seen-vars)
    (instance? clojure.lang.Var value)
    (localize-var value seen-vars)
    (or (sb/bottom-schema? value)
        (sb/join? value)
        (sb/valued-schema? value)
        (sb/variable? value))
    (localize-schema-base-value value seen-vars)
    (at/semantic-type-value? value)
    (localize-semantic-type value seen-vars)
    (or (vector? value)
        (set? value)
        (and (map? value) (not (record? value)))
        (seq? value))
    (localize-raw-collection value seen-vars)
    :else value))

(defn localize-value
  [value]
  (localize-value* value #{}))
