(ns skeptic.analysis.map-ops
  (:require [clojure.set :as set]
            [schema.core :as s]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.conditional-arms :as ca]
            [skeptic.analysis.map-ops.schema :as amos]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]))

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b opts)))

(defn- cast-ok?'
  [a b]
  ((requiring-resolve 'skeptic.analysis.cast.result/ok?) (check-cast' a b)))

(defn- value-satisfies-type?'
  [value type]
  ((requiring-resolve 'skeptic.analysis.value-check/value-satisfies-type?) value type))

(defn- leaf-overlap?'
  [source-type target-type]
  ((requiring-resolve 'skeptic.analysis.value-check/leaf-overlap?) source-type target-type))

(defn- as-type
  [value]
  (ato/normalize value))

(s/defn finite-exact-key-values :- (s/maybe #{s/Any})
  [type :- at/SemanticType]
  (let [type (ascs/optional-key-inner (as-type type))]
    (cond
      (at/value-type? type)
      #{(:value type)}

      (at/union-type? type)
      (let [member-values (map finite-exact-key-values (:members type))]
        (when (every? set? member-values)
          (apply set/union member-values)))

      :else
      nil)))

(s/defn map-key-query? :- s/Bool
  [query :- s/Any]
  (at/tagged-map? query ::map-key-query true))

(s/defn exact-key-query :- amos/ExactKeyQuery
  ([prov :- provs/Provenance value :- s/Any]
   (exact-key-query prov value nil))
  ([prov :- provs/Provenance value :- s/Any source-form :- s/Any]
   {::map-key-query true
    :kind :exact
    :prov prov
    :value value
    :source-form source-form}))

(s/defn domain-key-query :- {s/Keyword s/Any}
  ([type :- at/SemanticType]
   (domain-key-query type nil))
  ([type :- at/SemanticType source-form :- s/Any]
   {::map-key-query true
    :kind :domain
    :type (ato/normalize type)
    :source-form source-form}))

(s/defn exact-key-query? :- s/Bool
  [query :- s/Any]
  (and (map-key-query? query)
       (= :exact (:kind query))))

(s/defn query-key-type :- at/SemanticType
  [query :- s/Any]
  (if (exact-key-query? query)
    (ato/exact-value-type (:prov query) (:value query))
    (:type query)))

(s/defn exact-entry-kind :- s/Keyword
  [key-type :- at/SemanticType]
  (if (at/optional-key-type? key-type)
    :optional-explicit
    :required-explicit))

(s/defn descriptor-entry :- {s/Keyword s/Any}
  [entry-key :- s/Any entry-value :- s/Any kind :- s/Keyword]
  (let [key-type (as-type entry-key)
        inner-key-type (ascs/optional-key-inner key-type)]
    {:key entry-key
     :value entry-value
     :kind kind
     :key-type key-type
     :inner-key-type inner-key-type
     :exact-value (when (at/value-type? inner-key-type)
                    (:value inner-key-type))}))

(s/defn add-descriptor-entry :- {s/Keyword s/Any}
  [descriptor :- {s/Keyword s/Any} entry :- {s/Keyword s/Any}]
  (if-let [exact-value (:exact-value entry)]
    (case (:kind entry)
      :required-explicit (assoc-in descriptor [:required-exact exact-value] entry)
      :optional-explicit (assoc-in descriptor [:optional-exact exact-value] entry)
      (update descriptor :domain-entries conj entry))
    (update descriptor :domain-entries conj entry)))

(s/defn map-entry-descriptor :- {s/Keyword s/Any}
  [entries :- s/Any]
  (reduce (fn [descriptor [entry-key entry-value]]
            (let [key-type (as-type entry-key)]
              (if-let [exact-values (finite-exact-key-values key-type)]
                (let [key-prov (ato/derive-prov key-type)]
                  (reduce (fn [desc exact-value]
                            (add-descriptor-entry
                             desc
                             (descriptor-entry (ato/exact-value-type key-prov exact-value)
                                               entry-value
                                               (exact-entry-kind key-type))))
                          descriptor
                          exact-values))
                (add-descriptor-entry
                 descriptor
                 (descriptor-entry entry-key
                                   entry-value
                                   :domain-entry)))))
          {:entries entries
           :required-exact {}
           :optional-exact {}
           :domain-entries []}
          entries))

(s/defn effective-exact-entries :- s/Any
  [descriptor :- {s/Keyword s/Any}]
  (concat (vals (:required-exact descriptor))
          (->> (:optional-exact descriptor)
               (remove (fn [[value _entry]]
                         (contains? (:required-exact descriptor) value)))
               (map val))))

(s/defn exact-key-entry :- (s/maybe {s/Keyword s/Any})
  [descriptor :- {s/Keyword s/Any} exact-value :- s/Any]
  (or (get-in descriptor [:required-exact exact-value])
      (get-in descriptor [:optional-exact exact-value])))

(s/defn key-domain-covered? :- s/Bool
  [source-key :- at/SemanticType target-key :- at/SemanticType]
  (let [source-key (ascs/optional-key-inner (as-type source-key))
        target-key (ascs/optional-key-inner (as-type target-key))]
    (cond
      (at/value-type? source-key)
      (value-satisfies-type?' (:value source-key) target-key)

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
      (cast-ok?' source-key target-key))))

(s/defn key-domain-overlap? :- s/Bool
  [source-key :- at/SemanticType target-key :- at/SemanticType]
  (let [source-key (ascs/optional-key-inner (as-type source-key))
        target-key (ascs/optional-key-inner (as-type target-key))]
    (cond
      (or (at/dyn-type? source-key)
          (at/dyn-type? target-key)
          (at/placeholder-type? source-key)
          (at/placeholder-type? target-key)
          (at/inf-cycle-type? source-key)
          (at/inf-cycle-type? target-key))
      true

      (at/value-type? source-key)
      (value-satisfies-type?' (:value source-key) target-key)

      (at/value-type? target-key)
      (value-satisfies-type?' (:value target-key) source-key)

      (at/union-type? source-key)
      (some #(key-domain-overlap? % target-key) (:members source-key))

      (at/union-type? target-key)
      (some #(key-domain-overlap? source-key %) (:members target-key))

      (at/maybe-type? source-key)
      (key-domain-overlap? (:inner source-key) target-key)

      (at/maybe-type? target-key)
      (key-domain-overlap? source-key (:inner target-key))

      :else
      (or (cast-ok?' source-key target-key)
          (cast-ok?' target-key source-key)
          (leaf-overlap?' source-key target-key)))))

(s/defn exact-key-candidates :- [s/Any]
  [descriptor :- {s/Keyword s/Any} exact-value :- s/Any]
  (if-let [entry (exact-key-entry descriptor exact-value)]
    [entry]
    (->> (:domain-entries descriptor)
         (filter #(value-satisfies-type?' exact-value (:inner-key-type %)))
         vec)))

(s/defn domain-key-candidates :- [s/Any]
  [descriptor :- {s/Keyword s/Any} key-type :- at/SemanticType]
  (let [key-type (ascs/optional-key-inner (as-type key-type))]
    (vec
     (concat
      (filter #(value-satisfies-type?' (:exact-value %) key-type)
              (effective-exact-entries descriptor))
      (filter #(key-domain-overlap? key-type (:inner-key-type %))
              (:domain-entries descriptor))))))

(s/defn map-lookup-candidates :- [s/Any]
  [entries :- s/Any key-query :- s/Any]
  (let [descriptor (map-entry-descriptor entries)]
    (if (exact-key-query? key-query)
      (exact-key-candidates descriptor (:value key-query))
      (domain-key-candidates descriptor (query-key-type key-query)))))

(def no-default ::no-default)

(s/defn candidate-value-type :- (s/maybe at/SemanticType)
  [candidates :- [s/Any]]
  (when (seq candidates)
    (ato/union (map :value candidates))))

(s/defn map-get-type :- (s/maybe at/SemanticType)
  ([m :- at/SemanticType key :- s/Any]
   (map-get-type m key no-default))
  ([m :- at/SemanticType key :- s/Any default :- s/Any]
   (let [m (as-type m)
         key-query key
         default-provided? (not= default no-default)
         default-type (when default-provided?
                        (as-type default))]
     (cond
       (at/maybe-type? m)
       (ato/union
        [(or (map-get-type (:inner m) key-query default) (ato/dyn m))
         (or default-type (at/->MaybeT (ato/derive-prov m) (ato/dyn m)))])

       (at/union-type? m)
       (ato/union (keep #(map-get-type % key-query default) (:members m)))

       (at/map-type? m)
       (if-let [candidates (seq (map-lookup-candidates (:entries m) key-query))]
         (let [base-value (ato/union (map :value candidates))
               base-value (if (and (exact-key-query? key-query)
                                   (= 1 (count candidates))
                                   (= :optional-explicit (:kind (first candidates)))
                                   (not default-provided?))
                            (if (at/maybe-type? base-value)
                              base-value
                              (at/->MaybeT (ato/derive-prov base-value) base-value))
                            base-value)]
           (if default-provided?
             (ato/union [base-value (or default-type (ato/dyn m))])
             base-value))
         (if default-provided?
           default-type
           (ato/dyn m)))

       :else
       (if default-provided?
         (ato/union [(ato/dyn m) (or default-type (ato/dyn m))])
         (ato/dyn m))))))

(s/defn merge-map-types :- at/SemanticType
  [anchor-prov :- provs/Provenance types :- [at/SemanticType]]
  (let [types (mapv as-type types)]
    (cond
      (empty? types) (at/Dyn anchor-prov)
      (every? at/map-type? types) (at/->MapT (prov/with-refs anchor-prov (mapv prov/of types))
                                             (apply merge (map :entries types)))
      :else (apply ato/dyn types))))

(defn- exact-entry-value
  [entry-key]
  (let [inner-key (if (at/optional-key-type? entry-key) (:inner entry-key) entry-key)]
    (when (at/value-type? inner-key)
      (:value inner-key))))

(defn- optional-exact-entry-for?
  [entry-key exact-value]
  (and (at/optional-key-type? entry-key)
       (= exact-value (exact-entry-value entry-key))))

(defn- promote-optional-entry
  [entries exact-value]
  (into {}
        (map (fn [[entry-key entry-value]]
               (if (optional-exact-entry-for? entry-key exact-value)
                 [(:inner entry-key) entry-value]
                 [entry-key entry-value])))
        entries))

(defn- drop-optional-entry
  [entries exact-value]
  (into {}
        (remove (fn [[entry-key _entry-value]]
                  (optional-exact-entry-for? entry-key exact-value)))
        entries))

(defn- refine-map-by-exact-key
  [root-type exact-value polarity]
  (let [prov (ato/derive-prov root-type)
        descriptor (map-entry-descriptor (:entries root-type))
        exact-entry (exact-key-entry descriptor exact-value)
        candidates (exact-key-candidates descriptor exact-value)]
    (cond
      (nil? exact-entry)
      (if (seq candidates)
        root-type
        (if polarity (at/BottomType prov) root-type))

      polarity
      (case (:kind exact-entry)
        :optional-explicit (at/->MapT prov (promote-optional-entry (:entries root-type) exact-value))
        :required-explicit root-type
        root-type)

      (= :optional-explicit (:kind exact-entry))
      (at/->MapT prov (drop-optional-entry (:entries root-type) exact-value))

      (= :required-explicit (:kind exact-entry))
      (at/BottomType prov)

      :else
      root-type)))

(s/defn refine-by-contains-key :- at/SemanticType
  [type :- at/SemanticType key :- s/Any polarity :- s/Bool]
  (let [type (as-type type)]
    (cond
      (at/map-type? type)
      (refine-map-by-exact-key type key polarity)

      (at/union-type? type)
      (let [refined (keep (fn [member]
                            (let [r (refine-by-contains-key member key polarity)]
                              (when-not (at/bottom-type? r) r)))
                          (:members type))]
        (if (empty? refined)
          (at/BottomType (ato/derive-prov type))
          (ato/union refined)))

      (at/conditional-type? type)
      (let [anchor (prov/of type)
            refined (keep (fn [b]
                            (let [r (refine-by-contains-key (:type b) key polarity)]
                              (when-not (at/bottom-type? r) (assoc b :type r))))
                          (ca/effective-conditional-branches type))]
        (case (count refined)
          0 (at/BottomType anchor)
          1 (:type (first refined))
          (at/->ConditionalT anchor (vec refined))))

      (at/maybe-type? type)
      (let [inner (refine-by-contains-key (:inner type) key polarity)]
        (cond
          (at/bottom-type? inner) (if polarity (at/BottomType (ato/derive-prov type)) type)
          polarity inner
          :else (at/->MaybeT (ato/derive-prov type) inner)))

      :else
      type)))

(declare refine-map-path)

(defn- update-entry-by-exact-value
  [entries exact-value new-value]
  (into {} (map (fn [[k v]]
                  (let [inner (if (at/optional-key-type? k) (:inner k) k)]
                    [k (if (and (at/value-type? inner) (= (:value inner) exact-value))
                         new-value v)])))
        entries))

(defn- domain-candidate?
  [candidate]
  (nil? (:exact-value candidate)))

(defn- specialize-wildcard-entry
  "Add an explicit (s/optional-key key) -> narrowed entry to a wildcard-bearing
   entry map. Preserves the wildcard for other keys. No-ops when the narrowed
   type already matches the wildcard's value-type."
  [entries prov candidate key-query narrowed]
  (let [wildcard-val (:value candidate)]
    (if (at/type=? wildcard-val narrowed)
      entries
      (let [ev (ato/exact-value-type prov (:value key-query))
            opt-key (at/->OptionalKeyT prov ev)]
        (assoc entries opt-key narrowed)))))

(defn- write-back-entries
  [entries prov candidate key-query new-value presence-required?]
  (let [entries (if (and presence-required?
                         (exact-key-query? key-query)
                         (= :optional-explicit (:kind candidate)))
                  (promote-optional-entry entries (:value key-query))
                  entries)]
    (if (and (domain-candidate? candidate) (exact-key-query? key-query))
      (specialize-wildcard-entry entries prov candidate key-query new-value)
      (update-entry-by-exact-value entries (:exact-value candidate) new-value))))

(defn- handle-slot-bottom
  "Slot value narrowed to BottomType is sound for required-explicit (the
   required key cannot hold any value -> map unreachable) but NOT for
   optional or wildcard slots, where it just means the key cannot be
   present. Under `presence-required?`, optional-explicit is also bottom
   (the assumption proves the key must be present, so a bottom slot is
   unreachable). Returns the map shape that records the narrowed slot."
  [root-type prov candidate presence-required?]
  (case (:kind candidate)
    :required-explicit (at/BottomType prov)
    :optional-explicit (if presence-required?
                         (at/BottomType prov)
                         (at/->MapT prov (drop-optional-entry (:entries root-type)
                                                              (:exact-value candidate))))
    (at/->MapT prov (:entries root-type))))

(defn- refine-map-leaf
  [root-type key-query leaf-fn presence-required?]
  (let [prov (ato/derive-prov root-type)
        candidates (map-lookup-candidates (:entries root-type) key-query)
        val-type (candidate-value-type candidates)]
    (if (nil? val-type)
      root-type
      (let [narrowed (leaf-fn prov val-type)
            candidate (first candidates)]
        (if (at/bottom-type? narrowed)
          (handle-slot-bottom root-type prov candidate presence-required?)
          (at/->MapT prov (write-back-entries (:entries root-type) prov
                                              candidate key-query narrowed
                                              presence-required?)))))))

(defn- refine-map-inner
  [root-type key-query rest-path leaf-fn cond-fn presence-required?]
  (let [candidates (map-lookup-candidates (:entries root-type) key-query)
        prov (ato/derive-prov root-type)]
    (if (empty? candidates)
      root-type
      (let [candidate (first candidates)
            inner-type (:value candidate)
            refined-inner (refine-map-path inner-type rest-path leaf-fn cond-fn presence-required?)]
        (if (at/bottom-type? refined-inner)
          (handle-slot-bottom root-type prov candidate presence-required?)
          (at/->MapT prov (write-back-entries (:entries root-type) prov
                                              candidate key-query refined-inner
                                              presence-required?)))))))

(defn- refine-map-path-map
  [root-type path leaf-fn cond-fn presence-required?]
  (let [key-query (first path)
        rest-path (rest path)]
    (if (seq rest-path)
      (refine-map-inner root-type key-query rest-path leaf-fn cond-fn presence-required?)
      (refine-map-leaf root-type key-query leaf-fn presence-required?))))

(defn- refine-map-path-union
  [root-type path leaf-fn cond-fn presence-required?]
  (let [refined (keep (fn [member]
                        (let [r (refine-map-path member path leaf-fn cond-fn presence-required?)]
                          (when-not (at/bottom-type? r) r)))
                      (:members root-type))]
    (if (empty? refined)
      (at/BottomType (ato/derive-prov root-type))
      (ato/union refined))))

(defn- refine-map-path-maybe
  [root-type path leaf-fn cond-fn presence-required?]
  (let [refined-inner (refine-map-path (:inner root-type) path leaf-fn cond-fn presence-required?)]
    (cond
      (at/bottom-type? refined-inner) (at/BottomType (ato/derive-prov root-type))
      presence-required? refined-inner
      :else (at/->MaybeT (ato/derive-prov root-type) refined-inner))))

(defn- refine-map-path
  "Walk `root-type` along `path`. Apply `leaf-fn` (called as
   `(leaf-fn leaf-prov val-type)`) at the slot. ConditionalT nodes are
   handed to `cond-fn` (called as `(cond-fn cond-type path)`); pass nil to
   leave them unchanged. When `presence-required?`, optional-explicit
   entries along `path` are promoted to required-explicit and outer
   MaybeT wraps are dropped."
  [root-type path leaf-fn cond-fn presence-required?]
  (let [root-type (as-type root-type)]
    (cond
      (at/map-type? root-type)   (refine-map-path-map root-type path leaf-fn cond-fn presence-required?)
      (at/union-type? root-type) (refine-map-path-union root-type path leaf-fn cond-fn presence-required?)
      (at/maybe-type? root-type) (refine-map-path-maybe root-type path leaf-fn cond-fn presence-required?)
      (and cond-fn (at/conditional-type? root-type)) (cond-fn root-type path)
      :else root-type)))

(defn- values-leaf-fn
  [values polarity]
  (fn [prov val-type]
    (let [matching (filter #(value-satisfies-type?' % val-type) values)]
      (if polarity
        (if (empty? matching)
          (at/BottomType prov)
          (ato/union (map #(ato/exact-value-type prov %) matching)))
        (if (= (count matching) (count values))
          (at/BottomType prov)
          val-type)))))

(defn- values-cond-fn
  [values polarity presence-required?]
  (fn [cond-type path]
    (or (ca/route-conditional-by-values cond-type path values polarity)
        (let [anchor (prov/of cond-type)
              leaf (values-leaf-fn values polarity)
              refined (keep (fn [b]
                              (let [r (refine-map-path (:type b) path leaf nil presence-required?)]
                                (when-not (at/bottom-type? r) (assoc b :type r))))
                            (ca/effective-conditional-branches cond-type))]
          (case (count refined)
            0 (at/BottomType anchor)
            1 (:type (first refined))
            (at/->ConditionalT anchor (vec refined)))))))

(defn- predicate-excludes-nil?
  "True when applying `pred-info` at `polarity` to a nil-typed value yields
   Bottom — i.e. the predicate rules out nil at that polarity. Used to decide
   whether a path-narrowing assumption proves presence (which lets optional
   keys on the path be promoted to required, and outer MaybeT wraps be
   dropped). Reuses `partition-type-for-predicate` so any future predicate is
   auto-classified."
  [prov pred-info polarity]
  (at/bottom-type?
   (an/partition-type-for-predicate (ato/exact-value-type prov nil)
                                    pred-info polarity)))

(defn- values-exclude-nil?
  "True when the value-equality assumption rules out nil at the path under
   `polarity` — i.e. polarity-true and `values` contains no nil, or
   polarity-false and `values` contains nil."
  [values polarity]
  (if polarity
    (not-any? nil? values)
    (boolean (some nil? values))))

(s/defn refine-map-path-by-values :- at/SemanticType
  "Refine `root-type` by asserting that the value at the nested path `path`
   equals one of `values`. `polarity` true selects matching values; false
   selects non-matching."
  [root-type :- at/SemanticType path :- [s/Any] values :- [s/Any] polarity :- s/Bool]
  (let [presence-required? (values-exclude-nil? values polarity)]
    (refine-map-path root-type path
                     (values-leaf-fn values polarity)
                     (values-cond-fn values polarity presence-required?)
                     presence-required?)))

(s/defn refine-map-path-by-predicate :- at/SemanticType
  "Refine `root-type` by asserting that the value at `path` satisfies
   `pred-info` (`{:pred kw :class cls-or-nil}`) with the given `polarity`."
  [root-type :- at/SemanticType path :- [s/Any] pred-info :- {s/Keyword s/Any} polarity :- s/Bool]
  (let [presence-required? (predicate-excludes-nil? (ato/derive-prov root-type) pred-info polarity)]
    (letfn [(leaf-fn [_prov val-type]
              (an/partition-type-for-predicate val-type pred-info polarity))
            (cond-fn [cond-type cond-path]
              (let [anchor (prov/of cond-type)
                    branches (vec (:branches cond-type))
                    raw-path (ca/unwrap-exact-path cond-path)
                    live-indices (if raw-path
                                   (vec (remove
                                          #(ca/dispatch-incompatible-with-predicate?
                                             branches % pred-info raw-path polarity)
                                          (range (count branches))))
                                   (vec (range (count branches))))
                    live-set (set live-indices)
                    refined (keep (fn [k]
                                    (let [b (nth branches k)
                                          earlier-live (filter #(and (< % k) (live-set %))
                                                               (range k))
                                          earlier-descs (mapv #(:descriptor (nth branches %))
                                                              earlier-live)
                                          eff (reduce ca/refine-by-descriptor (:type b) earlier-descs)
                                          r (refine-map-path eff cond-path leaf-fn cond-fn
                                                             presence-required?)]
                                      (when-not (at/bottom-type? r) (assoc b :type r))))
                                  live-indices)]
                (case (count refined)
                  0 (at/BottomType anchor)
                  1 (:type (first refined))
                  (at/->ConditionalT anchor (vec refined)))))]
      (refine-map-path root-type path leaf-fn cond-fn presence-required?))))

(s/defn map-type-at-path :- (s/maybe at/SemanticType)
  "Return the Type stored at `path` within `root-type`, descending through
   map/union/maybe layers. Returns nil when the path cannot be resolved."
  [root-type :- at/SemanticType path :- [s/Any]]
  (let [root-type (as-type root-type)]
    (cond
      (empty? path) root-type
      (at/map-type? root-type)
      (let [candidates (map-lookup-candidates (:entries root-type) (first path))]
        (when-let [val-type (candidate-value-type candidates)]
          (map-type-at-path val-type (rest path))))
      (at/maybe-type? root-type) (map-type-at-path (:inner root-type) path)
      (at/union-type? root-type)
      (let [results (keep #(map-type-at-path % path) (:members root-type))]
        (when (seq results) (ato/union results)))
      :else nil)))
