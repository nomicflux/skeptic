(ns skeptic.analysis.type-ops
  (:require [schema.core :as s]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defn derive-prov
  "Merge attached provenance of typed inputs. Requires at least one input
  that carries prov — throws otherwise, signalling a caller without real
  provenance context."
  [& types]
  (or (reduce prov/merge-provenances nil (keep prov/of types))
      (throw (IllegalArgumentException.
              "derive-prov called without any typed input carrying provenance"))))

(defn literal-ground-type
  [prov value]
  (cond
    (integer? value) (at/->GroundT prov :int 'Int)
    (number? value) (at/->GroundT prov {:class (class value)} (symbol (.getSimpleName ^Class (class value))))
    (string? value) (at/->GroundT prov :str 'Str)
    (keyword? value) (at/->GroundT prov :keyword 'Keyword)
    (symbol? value) (at/->GroundT prov :symbol 'Symbol)
    (boolean? value) (at/->GroundT prov :bool 'Bool)
    :else nil))

(defn exact-value-type
  [prov value]
  (at/->ValueT prov (or (literal-ground-type prov value) (at/Dyn prov)) value))

(defn- invalid-normalize-type-input
  [value]
  (throw (IllegalArgumentException.
          (format "normalize-type only accepts canonical semantic types or internal type-like values, got %s"
                  (pr-str value)))))

(defn nil-value-type?
  "True when t is a singleton value type for nil, i.e. (s/eq nil) after normalization."
  [t]
  (and (at/value-type? t) (nil? (:value t))))

(defn- nil-bearing-member?
  [t]
  (or (at/maybe-type? t)
      (nil-value-type? t)
      (and (at/conditional-type? t)
           (some nil-bearing-member? (map second (:branches t))))))

(defn- maybe-bases-without-dyn
  [plain-members maybe-bases]
  (let [non-dyn (set (remove at/dyn-type? maybe-bases))]
    (if (and (some at/dyn-type? maybe-bases)
             (seq (concat plain-members non-dyn)))
      non-dyn
      maybe-bases)))

(defn nil-bearing-type-members
  [norm-fn prov members]
  (->> members
       (map #(norm-fn prov %))
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
                maybe-bases (maybe-bases-without-dyn plain-members maybe-bases)]
            {:nil-bearing? (boolean nil-bearing?)
             :has-nil-value? (boolean (some nil-value-type? nil-bearing-members))
             :members (at/dedup-types (concat plain-members maybe-bases))})))))

(defn- union-type-with-normalize
  [norm-fn prov members]
  (let [{:keys [nil-bearing? has-nil-value? members]} (nil-bearing-type-members norm-fn prov members)
        base (cond
               (empty? members) (at/Dyn prov)
               (= 1 (count members)) (first members)
               :else (at/->UnionT prov members))]
    (cond
      (and nil-bearing? has-nil-value? (empty? members)) (exact-value-type prov nil)
      nil-bearing? (at/->MaybeT prov base)
      :else base)))

(defn normalize-type
  [prov value]
  (cond
    (at/semantic-type-value? value) value
    (nil? value) (at/->MaybeT prov (at/Dyn prov))
    (sb/schema-literal? value) (exact-value-type prov value)
    (s/optional-key? value) (at/->OptionalKeyT prov (normalize-type prov (:k value)))
    (and (map? value) (not (record? value)))
    (at/->MapT prov (into {}
                          (map (fn [[k v]]
                                 [(normalize-type prov k)
                                  (normalize-type prov v)]))
                          value))
    (vector? value) (at/->VectorT prov (mapv #(normalize-type prov %) value) (= 1 (count value)))
    (set? value) (at/->SetT prov (into #{} (map #(normalize-type prov %)) value) (= 1 (count value)))
    (seq? value) (at/->SeqT prov (mapv #(normalize-type prov %) value) (= 1 (count value)))
    :else (invalid-normalize-type-input value)))

(defn normalize-intersection-members
  [prov members]
  (->> members
       (map #(normalize-type prov %))
       (mapcat (fn [member]
                 (if (at/intersection-type? member)
                   (:members member)
                   [member])))
       set))

(defn union-type
  [prov members]
  (union-type-with-normalize normalize-type prov members))

(defn intersection-type
  [prov members]
  (let [members (normalize-intersection-members prov members)]
    (cond
      (empty? members) (at/Dyn prov)
      (= 1 (count members)) (first members)
      :else (at/->IntersectionT prov members))))

(defn de-maybe-type
  [prov type]
  (let [type (normalize-type prov type)]
    (cond
      (at/maybe-type? type)
      (:inner type)

      (at/union-type? type)
      (union-type prov (map (fn [member]
                              (cond
                                (at/maybe-type? member) (:inner member)
                                (at/conditional-type? member) (de-maybe-type prov member)
                                :else member))
                            (:members type)))

      (at/conditional-type? type)
      (at/->ConditionalT (prov/of type)
                         (mapv (fn [[pred typ slot3]]
                                 [pred (de-maybe-type prov typ) slot3])
                               (:branches type)))

      :else
      type)))

(defn unknown-type?
  [prov type]
  (let [type (normalize-type prov type)]
    (cond
      (at/dyn-type? type) true
      (at/placeholder-type? type) true
      (at/inf-cycle-type? type) true
      (at/maybe-type? type) (unknown-type? prov (:inner type))
      (at/union-type? type) (some #(unknown-type? prov %) (:members type))
      (at/conditional-type? type) (some #(unknown-type? prov %) (map second (:branches type)))
      :else false)))

(defn normalize
  "Convenience: derives prov from the typed input."
  [value]
  (normalize-type (derive-prov value) value))

(defn union
  "Convenience: derives prov from members."
  [members]
  (union-type (apply derive-prov members) members))

(defn intersection
  "Convenience: derives prov from members."
  [members]
  (intersection-type (apply derive-prov members) members))

(defn de-maybe
  "Convenience: derives prov from the typed input."
  [type]
  (de-maybe-type (derive-prov type) type))

(defn unknown?
  "Convenience: derives prov from the typed input."
  [type]
  (unknown-type? (derive-prov type) type))

(defn dyn
  "Convenience: produce an at/Dyn with prov derived from typed inputs."
  [& types]
  (at/Dyn (apply derive-prov types)))

(defn bottom
  "Convenience: produce an at/BottomType with prov derived from typed inputs."
  [& types]
  (at/BottomType (apply derive-prov types)))

(defn numeric-dyn
  "Convenience: produce an at/NumericDyn with prov derived from typed inputs."
  [& types]
  (at/NumericDyn (apply derive-prov types)))
