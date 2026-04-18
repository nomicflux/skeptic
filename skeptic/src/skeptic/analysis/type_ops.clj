(ns skeptic.analysis.type-ops
  (:require [schema.core :as s]
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

(defn- nil-value-type?
  [t]
  (and (at/value-type? t) (nil? (:value t))))

(defn- nil-bearing-member?
  [t]
  (or (at/maybe-type? t) (nil-value-type? t)))

(defn nil-bearing-type-members
  [norm-fn members]
  (->> members
       (map norm-fn)
       (mapcat (fn [member]
                 (if (at/union-type? member)
                   (:members member)
                   [member])))
       ((fn [members]
          (let [nil-bearing? (some nil-bearing-member? members)
                {nil-bearing-members true
                 plain-members false} (group-by nil-bearing-member? members)
                maybe-bases (->> nil-bearing-members
                                 (remove nil-value-type?)
                                 (map :inner)
                                 set)
                maybe-bases (if (and (contains? maybe-bases at/Dyn)
                                     (seq (concat plain-members
                                                  (disj maybe-bases at/Dyn))))
                              (disj maybe-bases at/Dyn)
                              maybe-bases)]
            {:nil-bearing? (boolean nil-bearing?)
             :has-nil-value? (boolean (some nil-value-type? nil-bearing-members))
             :members (into (set plain-members) maybe-bases)})))))

(defn- union-type-with-normalize
  [norm-fn members]
  (let [{:keys [nil-bearing? has-nil-value? members]} (nil-bearing-type-members norm-fn members)
        base (cond
               (empty? members) at/Dyn
               (= 1 (count members)) (first members)
               :else (at/->UnionT members))]
    (cond
      (and nil-bearing? has-nil-value? (empty? members)) (exact-value-type nil)
      nil-bearing? (at/->MaybeT base)
      :else base)))

(defn normalize-type
  [value]
  (cond
    (at/conditional-type? value)
    (union-type-with-normalize normalize-type (map second (:branches value)))

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

(defn normalize-intersection-members
  [members]
  (->> members
       (map normalize-type)
       (mapcat (fn [member]
                 (if (at/intersection-type? member)
                   (:members member)
                   [member])))
       set))

(defn union-type
  [members]
  (union-type-with-normalize normalize-type members))

(defn normalize-type-for-declared-type
  "Preserves top-level ConditionalT (with normalized branch types) so schema-declared
  conditional params keep predicates for case narrowing. Else same as normalize-type."
  [value]
  (if (at/conditional-type? value)
    (at/->ConditionalT (mapv (fn [[pred typ]] [pred (normalize-type typ)])
                       (:branches value)))
    (normalize-type value)))

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
      (at/inf-cycle-type? type) true
      (at/maybe-type? type) (unknown-type? (:inner type))
      (at/union-type? type) (some unknown-type? (:members type))
      :else false)))
