(ns skeptic.analysis.type-ops
  (:require [schema.core :as s]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]))

(s/defn derive-prov :- provs/Provenance
  "Merge attached provenance of typed inputs. Requires at least one input —
  throws otherwise, signalling a caller without real provenance context."
  [& types :- [ats/SemanticType]]
  (if (seq types)
    (reduce prov/merge-provenances (map prov/of types))
    (throw (IllegalArgumentException.
            "derive-prov called without any typed input carrying provenance"))))

(s/defn literal-ground-type :- (s/maybe ats/SemanticType)
  [prov  :- provs/Provenance
   value :- s/Any]
  (cond
    (integer? value) (at/->GroundT prov :int 'Int)
    (number? value) (at/->GroundT prov {:class (class value)} (symbol (.getSimpleName ^Class (class value))))
    (string? value) (at/->GroundT prov :str 'Str)
    (keyword? value) (at/->GroundT prov :keyword 'Keyword)
    (symbol? value) (at/->GroundT prov :symbol 'Symbol)
    (boolean? value) (at/->GroundT prov :bool 'Bool)
    :else nil))

(s/defn exact-value-type :- ats/SemanticType
  [prov  :- provs/Provenance
   value :- s/Any]
  (at/->ValueT prov (or (literal-ground-type prov value) (at/Dyn prov)) value))

(defn- invalid-normalize-type-input
  [value]
  (throw (IllegalArgumentException.
          (format "normalize-type only accepts canonical semantic types or internal type-like values, got %s"
                  (pr-str value)))))

(s/defn nil-value-type? :- s/Bool
  "True when t is a singleton value type for nil, i.e. (s/eq nil) after normalization."
  [t :- s/Any]
  (and (at/value-type? t) (nil? (:value t))))

(defn- nil-bearing-member?
  [t]
  (or (at/maybe-type? t)
      (nil-value-type? t)
      (and (at/conditional-type? t)
           (some nil-bearing-member? (map second (:branches t))))))

(defn- non-nil-bases
  [t]
  (cond
    (nil-value-type? t) []
    (at/maybe-type? t) [(:inner t)]
    (at/conditional-type? t) (mapcat (comp non-nil-bases second) (:branches t))
    :else [t]))

(defn- maybe-bases-without-dyn
  [plain-members maybe-bases]
  (let [non-dyn (set (remove at/dyn-type? maybe-bases))]
    (if (and (some at/dyn-type? maybe-bases)
             (seq (concat plain-members non-dyn)))
      non-dyn
      maybe-bases)))

(s/defn nil-bearing-type-members :- {:nil-bearing?    s/Bool
                                     :has-nil-value?  s/Bool
                                     :members         #{ats/SemanticType}}
  [norm-fn :- (s/pred fn?)
   prov    :- provs/Provenance
   members :- s/Any]
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
                maybe-bases (into #{} (mapcat non-nil-bases) nil-bearing-members)
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

(s/defn normalize-type :- ats/SemanticType
  [prov  :- provs/Provenance
   value :- s/Any]
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
    (vector? value) (at/->VectorT prov (mapv #(normalize-type prov %) value) nil)
    (set? value) (at/->SetT prov (into #{} (map #(normalize-type prov %)) value) (= 1 (count value)))
    (seq? value) (at/->SeqT prov (mapv #(normalize-type prov %) value) nil)
    :else (invalid-normalize-type-input value)))

(s/defn normalize-intersection-members :- #{ats/SemanticType}
  [prov    :- provs/Provenance
   members :- s/Any]
  (->> members
       (map #(normalize-type prov %))
       (mapcat (fn [member]
                 (if (at/intersection-type? member)
                   (:members member)
                   [member])))
       set))

(s/defn union-type :- ats/SemanticType
  [prov    :- provs/Provenance
   members :- s/Any]
  (union-type-with-normalize normalize-type prov members))

(s/defn intersection-type :- ats/SemanticType
  [prov    :- provs/Provenance
   members :- s/Any]
  (let [members (normalize-intersection-members prov members)]
    (cond
      (empty? members) (at/Dyn prov)
      (= 1 (count members)) (first members)
      :else (at/->IntersectionT prov members))))

(s/defn de-maybe-type :- ats/SemanticType
  [prov :- provs/Provenance
   type :- s/Any]
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

(s/defn uninformative-for-narrowing? :- s/Bool
  "True when t carries no shape that would refine a narrowing partition.
  Distinct from `at/dyn-type?` because narrowing must also bail on
  unresolved resolver states (PlaceholderT, InfCycleT) and on structural
  carriers whose members include any of the above."
  [t :- ats/SemanticType]
  (boolean
   (cond
     (at/dyn-type? t) true
     (at/placeholder-type? t) true
     (at/inf-cycle-type? t) true
     (at/maybe-type? t) (uninformative-for-narrowing? (:inner t))
     (at/union-type? t) (some uninformative-for-narrowing? (:members t))
     (at/conditional-type? t) (some uninformative-for-narrowing? (map second (:branches t)))
     :else false)))

(s/defn normalize :- ats/SemanticType
  "Convenience: derives prov from the typed input."
  [value :- ats/SemanticType]
  (normalize-type (derive-prov value) value))

(s/defn union :- ats/SemanticType
  "Convenience: derives prov from members."
  [members :- [ats/SemanticType]]
  (union-type (apply derive-prov members) members))

(s/defn intersection :- ats/SemanticType
  "Convenience: derives prov from members."
  [members :- [ats/SemanticType]]
  (intersection-type (apply derive-prov members) members))

(s/defn de-maybe :- ats/SemanticType
  "Convenience: derives prov from the typed input."
  [type :- ats/SemanticType]
  (de-maybe-type (derive-prov type) type))

(s/defn dyn :- ats/SemanticType
  "Convenience: produce an at/Dyn with prov derived from typed inputs."
  [& types :- [ats/SemanticType]]
  (at/Dyn (apply derive-prov types)))

(s/defn bottom :- ats/SemanticType
  "Convenience: produce an at/BottomType with prov derived from typed inputs."
  [& types :- [ats/SemanticType]]
  (at/BottomType (apply derive-prov types)))

(s/defn numeric-dyn :- ats/SemanticType
  "Convenience: produce an at/NumericDyn with prov derived from typed inputs."
  [& types :- [ats/SemanticType]]
  (at/NumericDyn (apply derive-prov types)))
