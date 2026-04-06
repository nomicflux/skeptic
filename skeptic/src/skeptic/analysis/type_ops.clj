(ns skeptic.analysis.type-ops
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(defn literal-ground-type
  [value]
  (cond
    (integer? value) (at/->GroundT :int 'Int)
    (string? value) (at/->GroundT :str 'Str)
    (keyword? value) (at/->GroundT :keyword 'Keyword)
    (symbol? value) (at/->GroundT :symbol 'Symbol)
    (boolean? value) (at/->GroundT :bool 'Bool)
    :else nil))

(defn exact-value-type
  [value]
  (at/->ValueT (or (literal-ground-type value) at/Dyn) value))

(defn- invalid-normalize-type-input
  [value]
  (throw (IllegalArgumentException.
          (format "normalize-type only accepts canonical semantic types or internal type-like values, got %s"
                  (pr-str value)))))

(defn normalize-type
  [value]
  (cond
    (at/semantic-type-value? value) value
    (nil? value) (at/->MaybeT at/Dyn)
    (sb/schema-literal? value) (exact-value-type value)
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
    :else (invalid-normalize-type-input value)))

(defn- import-schema-type'
  [value]
  ((requiring-resolve 'skeptic.analysis.bridge/import-schema-type) value))

(defn- schema-domain-value?
  [value]
  ((requiring-resolve 'skeptic.analysis.bridge.canonicalize/schema?) value))

(defn coerce-boundary-type
  [value]
  (let [localized (abl/localize-value value)]
    (cond
      (schema-domain-value? localized)
      (import-schema-type' localized)

      (at/semantic-type-value? localized)
      localized

      (nil? localized)
      (at/->MaybeT at/Dyn)

      (sb/schema-literal? localized)
      (exact-value-type localized)

      (s/optional-key? localized)
      (at/->OptionalKeyT (coerce-boundary-type (:k localized)))

      (and (map? localized) (not (record? localized)))
      (at/->MapT (into {}
                       (map (fn [[k v]]
                              [(coerce-boundary-type k)
                               (coerce-boundary-type v)]))
                       localized))

      (vector? localized)
      (at/->VectorT (mapv coerce-boundary-type localized) (= 1 (count localized)))

      (set? localized)
      (at/->SetT (into #{} (map coerce-boundary-type) localized) (= 1 (count localized)))

      (seq? localized)
      (at/->SeqT (mapv coerce-boundary-type localized) (= 1 (count localized)))

      :else
      (invalid-normalize-type-input localized))))

(defn nil-bearing-type-members
  [members]
  (->> members
       (map coerce-boundary-type)
       (mapcat (fn [member]
                 (if (at/union-type? member)
                   (:members member)
                   [member])))
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
       (map coerce-boundary-type)
       (mapcat (fn [member]
                 (if (at/intersection-type? member)
                   (:members member)
                   [member])))
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

(defn unknown-type?
  [type]
  (let [type (normalize-type type)]
    (cond
      (at/dyn-type? type) true
      (at/placeholder-type? type) true
      (at/maybe-type? type) (unknown-type? (:inner type))
      (at/union-type? type) (some unknown-type? (:members type))
      :else false)))
