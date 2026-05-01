(ns skeptic.analysis.map-ops
  (:require [clojure.set :as set]
            [schema.core :as s]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]))

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b opts)))

(defn- narrow-conditional-by-discriminator'
  [anchor-prov branches path values]
  ((requiring-resolve 'skeptic.analysis.annotate.match/narrow-conditional-by-discriminator)
   anchor-prov branches path values))


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
  [type :- ats/SemanticType]
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

(def map-key-query-tag
  ::map-key-query)

(s/defn map-key-query? :- s/Bool
  [query :- s/Any]
  (at/tagged-map? query map-key-query-tag true))

(s/defn exact-key-query :- {s/Keyword s/Any}
  ([prov :- provs/Provenance value :- s/Any]
   (exact-key-query prov value nil))
  ([prov :- provs/Provenance value :- s/Any source-form :- s/Any]
   {map-key-query-tag true
    :kind :exact
    :prov prov
    :value value
    :source-form source-form}))

(s/defn domain-key-query :- {s/Keyword s/Any}
  ([type :- ats/SemanticType]
   (domain-key-query type nil))
  ([type :- ats/SemanticType source-form :- s/Any]
   {map-key-query-tag true
    :kind :domain
    :type (ato/normalize type)
    :source-form source-form}))

(s/defn exact-key-query? :- s/Bool
  [query :- s/Any]
  (and (map-key-query? query)
       (= :exact (:kind query))))

(s/defn query-key-type :- ats/SemanticType
  [query :- s/Any]
  (if (exact-key-query? query)
    (ato/exact-value-type (:prov query) (:value query))
    (:type query)))

(s/defn exact-entry-kind :- s/Keyword
  [key-type :- ats/SemanticType]
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
  [source-key :- ats/SemanticType target-key :- ats/SemanticType]
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
  [source-key :- ats/SemanticType target-key :- ats/SemanticType]
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
  [descriptor :- {s/Keyword s/Any} key-type :- ats/SemanticType]
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

(s/defn candidate-value-type :- (s/maybe ats/SemanticType)
  [candidates :- [s/Any]]
  (when (seq candidates)
    (ato/union (map :value candidates))))

(s/defn map-get-type :- (s/maybe ats/SemanticType)
  ([m :- ats/SemanticType key :- s/Any]
   (map-get-type m key no-default))
  ([m :- ats/SemanticType key :- s/Any default :- s/Any]
   (let [m (as-type m)
         key-query key
         default-provided? (not= default no-default)
         default-type (when default-provided?
                        (as-type default))]
     (cond
       (at/maybe-type? m)
       (ato/union
        [(map-get-type (:inner m) key-query default)
         (or default-type (at/->MaybeT (ato/derive-prov m) (ato/dyn m)))])

       (at/union-type? m)
       (ato/union (map #(map-get-type % key-query default) (:members m)))

       (at/map-type? m)
       (if-let [candidates (seq (map-lookup-candidates (:entries m) key-query))]
         (let [base-value (candidate-value-type candidates)
               base-value (if (and (exact-key-query? key-query)
                                   (= 1 (count candidates))
                                   (= :optional-explicit (:kind (first candidates)))
                                   (not default-provided?))
                            (if (at/maybe-type? base-value)
                              base-value
                              (at/->MaybeT (ato/derive-prov base-value) base-value))
                            base-value)]
           (if default-provided?
             (ato/union [base-value default-type])
             base-value))
         (if default-provided?
           default-type
           (ato/dyn m)))

       :else
       (if default-provided?
         (ato/union [(ato/dyn m) default-type])
         (ato/dyn m))))))

(s/defn merge-map-types :- ats/SemanticType
  [anchor-prov :- provs/Provenance types :- [ats/SemanticType]]
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

(s/defn refine-by-contains-key :- ats/SemanticType
  [type :- ats/SemanticType key :- s/Any polarity :- s/Bool]
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
      (let [refined (keep (fn [[_pred branch]]
                            (let [r (refine-by-contains-key branch key polarity)]
                              (when-not (at/bottom-type? r) r)))
                          (:branches type))]
        (if (empty? refined)
          (at/BottomType (ato/derive-prov type))
          (ato/union refined)))

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

(defn- refine-map-leaf
  [root-type key-query leaf-fn]
  (let [prov (ato/derive-prov root-type)
        candidates (map-lookup-candidates (:entries root-type) key-query)
        val-type (candidate-value-type candidates)]
    (if (nil? val-type)
      root-type
      (let [narrowed (leaf-fn prov val-type)]
        (if (at/bottom-type? narrowed)
          (at/BottomType prov)
          (at/->MapT prov (update-entry-by-exact-value (:entries root-type)
                                                       (:exact-value (first candidates))
                                                       narrowed)))))))

(defn- refine-map-inner
  [root-type key-query rest-path leaf-fn cond-fn]
  (let [candidates (map-lookup-candidates (:entries root-type) key-query)]
    (if (empty? candidates)
      root-type
      (let [candidate (first candidates)
            inner-type (:value candidate)
            refined-inner (refine-map-path inner-type rest-path leaf-fn cond-fn)]
        (if (at/bottom-type? refined-inner)
          (at/BottomType (ato/derive-prov root-type))
          (at/->MapT (ato/derive-prov root-type)
                     (update-entry-by-exact-value
                      (:entries root-type) (:exact-value candidate) refined-inner)))))))

(defn- refine-map-path-map
  [root-type path leaf-fn cond-fn]
  (let [key-query (first path)
        rest-path (rest path)]
    (if (seq rest-path)
      (refine-map-inner root-type key-query rest-path leaf-fn cond-fn)
      (refine-map-leaf root-type key-query leaf-fn))))

(defn- refine-map-path-union
  [root-type path leaf-fn cond-fn]
  (let [refined (keep (fn [member]
                        (let [r (refine-map-path member path leaf-fn cond-fn)]
                          (when-not (at/bottom-type? r) r)))
                      (:members root-type))]
    (if (empty? refined)
      (at/BottomType (ato/derive-prov root-type))
      (ato/union refined))))

(defn- refine-map-path-maybe
  [root-type path leaf-fn cond-fn]
  (let [refined-inner (refine-map-path (:inner root-type) path leaf-fn cond-fn)]
    (if (at/bottom-type? refined-inner)
      (at/BottomType (ato/derive-prov root-type))
      (at/->MaybeT (ato/derive-prov root-type) refined-inner))))

(defn- refine-map-path
  "Walk `root-type` along `path`. Apply `leaf-fn` (called as
   `(leaf-fn leaf-prov val-type)`) at the slot. ConditionalT nodes are
   handed to `cond-fn` (called as `(cond-fn cond-type path)`); pass nil to
   leave them unchanged."
  [root-type path leaf-fn cond-fn]
  (let [root-type (as-type root-type)]
    (cond
      (at/map-type? root-type)   (refine-map-path-map root-type path leaf-fn cond-fn)
      (at/union-type? root-type) (refine-map-path-union root-type path leaf-fn cond-fn)
      (at/maybe-type? root-type) (refine-map-path-maybe root-type path leaf-fn cond-fn)
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
  [values]
  (fn [cond-type path]
    (narrow-conditional-by-discriminator'
     (prov/of cond-type) (:branches cond-type) path values)))

(s/defn refine-map-path-by-values :- ats/SemanticType
  "Refine `root-type` by asserting that the value at the nested path `path`
   equals one of `values`. `polarity` true selects matching values; false
   selects non-matching."
  [root-type :- ats/SemanticType path :- [s/Any] values :- [s/Any] polarity :- s/Bool]
  (refine-map-path root-type path
                   (values-leaf-fn values polarity)
                   (values-cond-fn values)))

(s/defn refine-map-path-by-predicate :- ats/SemanticType
  "Refine `root-type` by asserting that the value at `path` satisfies
   `pred-info` (`{:pred kw :class cls-or-nil}`) with the given `polarity`."
  [root-type :- ats/SemanticType path :- [s/Any] pred-info :- {s/Keyword s/Any} polarity :- s/Bool]
  (refine-map-path root-type path
                   (fn [_prov val-type]
                     (an/partition-type-for-predicate val-type pred-info polarity))
                   nil))

(s/defn map-type-at-path :- (s/maybe ats/SemanticType)
  "Return the Type stored at `path` within `root-type`, descending through
   map/union/maybe layers. Returns nil when the path cannot be resolved."
  [root-type :- ats/SemanticType path :- [s/Any]]
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
