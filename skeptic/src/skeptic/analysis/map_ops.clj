(ns skeptic.analysis.map-ops
  (:require [clojure.set :as set]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b opts)))

(defn- narrow-conditional-by-discriminator'
  [anchor-prov branches path values opts]
  ((requiring-resolve 'skeptic.analysis.annotate.match/narrow-conditional-by-discriminator)
   anchor-prov branches path values opts))


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

(defn finite-exact-key-values
  [type]
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

(defn map-key-query?
  [query]
  (at/tagged-map? query map-key-query-tag true))

(defn exact-key-query
  ([prov value]
   (exact-key-query prov value nil))
  ([prov value source-form]
   {map-key-query-tag true
    :kind :exact
    :prov prov
    :value value
    :source-form source-form}))

(defn domain-key-query
  ([type]
   (domain-key-query type nil))
  ([type source-form]
   {map-key-query-tag true
    :kind :domain
    :type (ato/normalize type)
    :source-form source-form}))

(defn exact-key-query?
  [query]
  (and (map-key-query? query)
       (= :exact (:kind query))))

(defn query-key-type
  [query]
  (if (exact-key-query? query)
    (ato/exact-value-type (:prov query) (:value query))
    (:type query)))

(defn exact-entry-kind
  [key-type]
  (if (at/optional-key-type? key-type)
    :optional-explicit
    :required-explicit))

(defn descriptor-entry
  [entry-key entry-value kind]
  (let [key-type (as-type entry-key)
        inner-key-type (ascs/optional-key-inner key-type)]
    {:key entry-key
     :value entry-value
     :kind kind
     :key-type key-type
     :inner-key-type inner-key-type
     :exact-value (when (at/value-type? inner-key-type)
                    (:value inner-key-type))}))

(defn add-descriptor-entry
  [descriptor entry]
  (if-let [exact-value (:exact-value entry)]
    (case (:kind entry)
      :required-explicit (assoc-in descriptor [:required-exact exact-value] entry)
      :optional-explicit (assoc-in descriptor [:optional-exact exact-value] entry)
      (update descriptor :domain-entries conj entry))
    (update descriptor :domain-entries conj entry)))

(defn map-entry-descriptor
  [entries]
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

(defn effective-exact-entries
  [descriptor]
  (concat (vals (:required-exact descriptor))
          (->> (:optional-exact descriptor)
               (remove (fn [[value _entry]]
                         (contains? (:required-exact descriptor) value)))
               (map val))))

(defn exact-key-entry
  [descriptor exact-value]
  (or (get-in descriptor [:required-exact exact-value])
      (get-in descriptor [:optional-exact exact-value])))

(defn key-domain-covered?
  [source-key target-key]
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

(defn key-domain-overlap?
  [source-key target-key]
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

(defn exact-key-candidates
  [descriptor exact-value]
  (if-let [entry (exact-key-entry descriptor exact-value)]
    [entry]
    (->> (:domain-entries descriptor)
         (filter #(value-satisfies-type?' exact-value (:inner-key-type %)))
         vec)))

(defn domain-key-candidates
  [descriptor key-type]
  (let [key-type (ascs/optional-key-inner (as-type key-type))]
    (vec
     (concat
      (filter #(value-satisfies-type?' (:exact-value %) key-type)
              (effective-exact-entries descriptor))
      (filter #(key-domain-overlap? key-type (:inner-key-type %))
              (:domain-entries descriptor))))))

(defn map-lookup-candidates
  [entries key-query]
  (let [descriptor (map-entry-descriptor entries)]
    (if (exact-key-query? key-query)
      (exact-key-candidates descriptor (:value key-query))
      (domain-key-candidates descriptor (query-key-type key-query)))))

(def no-default ::no-default)

(defn candidate-value-type
  [candidates]
  (when (seq candidates)
    (ato/union (map :value candidates))))

(defn map-get-type
  ([m key]
   (map-get-type m key no-default))
  ([m key default]
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

(defn merge-map-types
  [anchor-prov types]
  (let [types (mapv as-type types)]
    (cond
      (empty? types) (at/Dyn anchor-prov)
      (every? at/map-type? types) (at/->MapT (prov/with-refs anchor-prov (mapv prov/of types))
                                             (apply merge (map :entries types)))
      :else (apply ato/dyn types))))

(declare refine-map-path-by-values)

(defn- update-entry-by-exact-value
  [entries exact-value new-value]
  (into {} (map (fn [[k v]]
                  (let [inner (if (at/optional-key-type? k) (:inner k) k)]
                    [k (if (and (at/value-type? inner) (= (:value inner) exact-value))
                         new-value v)])))
        entries))

(defn- narrow-value-to-literals
  [prov val-type values polarity]
  (let [matching (filter #(value-satisfies-type?' % val-type) values)]
    (if polarity
      (if (empty? matching)
        (at/BottomType prov)
        (ato/union (map #(ato/exact-value-type prov %) matching)))
      (if (= (count matching) (count values))
        (at/BottomType prov)
        val-type))))

(defn- refine-map-leaf
  [root-type key-query values polarity]
  (let [prov (ato/derive-prov root-type)
        candidates (map-lookup-candidates (:entries root-type) key-query)
        val-type (candidate-value-type candidates)]
    (if (nil? val-type)
      root-type
      (let [narrowed (narrow-value-to-literals prov val-type values polarity)]
        (if (at/bottom-type? narrowed)
          (at/BottomType prov)
          (at/->MapT prov (update-entry-by-exact-value (:entries root-type)
                                                       (:exact-value (first candidates))
                                                       narrowed)))))))

(defn- refine-map-inner
  [root-type key-query rest-path values polarity]
  (let [candidates (map-lookup-candidates (:entries root-type) key-query)]
    (if (empty? candidates)
      root-type
      (let [candidate (first candidates)
            inner-type (:value candidate)
            refined-inner (refine-map-path-by-values inner-type rest-path values polarity)]
        (if (at/bottom-type? refined-inner)
          (at/BottomType (ato/derive-prov root-type))
          (at/->MapT (ato/derive-prov root-type)
                     (update-entry-by-exact-value
                      (:entries root-type) (:exact-value candidate) refined-inner)))))))

(defn- refine-map-path-map
  [root-type path values polarity]
  (let [key-query (first path)
        rest-path (rest path)]
    (if (seq rest-path)
      (refine-map-inner root-type key-query rest-path values polarity)
      (refine-map-leaf root-type key-query values polarity))))

(defn- refine-map-path-union
  [root-type path values polarity]
  (let [refined (keep (fn [member]
                        (let [r (refine-map-path-by-values member path values polarity)]
                          (when-not (at/bottom-type? r) r)))
                      (:members root-type))]
    (if (empty? refined)
      (at/BottomType (ato/derive-prov root-type))
      (ato/union refined))))

(defn- refine-map-path-maybe
  [root-type path values polarity]
  (let [refined-inner (refine-map-path-by-values (:inner root-type) path values polarity)]
    (if (at/bottom-type? refined-inner)
      (at/BottomType (ato/derive-prov root-type))
      (at/->MaybeT (ato/derive-prov root-type) refined-inner))))

(defn- refine-map-path-conditional
  [root-type path values]
  (narrow-conditional-by-discriminator'
   (prov/of root-type) (:branches root-type) path values {:drop-discriminator? false}))

(defn refine-map-path-by-values
  "Refine `root-type` by asserting that the value at the nested path
   `path` (non-empty vector of key-queries) equals one of `values`.
   `polarity` true selects matching values; false selects non-matching.
   Returns the refined type."
  [root-type path values polarity]
  (let [root-type (as-type root-type)]
    (cond
      (at/map-type? root-type)     (refine-map-path-map root-type path values polarity)
      (at/union-type? root-type)   (refine-map-path-union root-type path values polarity)
      (at/maybe-type? root-type)   (refine-map-path-maybe root-type path values polarity)
      (at/conditional-type? root-type) (refine-map-path-conditional root-type path values)
      :else root-type)))

(declare refine-map-path-by-predicate map-type-at-path)

(defn- refine-map-leaf-by-predicate
  [root-type key-query pred-info polarity]
  (let [prov (ato/derive-prov root-type)
        candidates (map-lookup-candidates (:entries root-type) key-query)
        val-type (candidate-value-type candidates)]
    (if (nil? val-type)
      root-type
      (let [narrowed (an/partition-type-for-predicate val-type pred-info polarity)]
        (if (at/bottom-type? narrowed)
          (at/BottomType prov)
          (at/->MapT prov (update-entry-by-exact-value (:entries root-type)
                                                       (:exact-value (first candidates))
                                                       narrowed)))))))

(defn- refine-map-inner-by-predicate
  [root-type key-query rest-path pred-info polarity]
  (let [candidates (map-lookup-candidates (:entries root-type) key-query)]
    (if (empty? candidates)
      root-type
      (let [candidate (first candidates)
            inner-type (:value candidate)
            refined-inner (refine-map-path-by-predicate inner-type rest-path pred-info polarity)]
        (if (at/bottom-type? refined-inner)
          (at/BottomType (ato/derive-prov root-type))
          (at/->MapT (ato/derive-prov root-type)
                     (update-entry-by-exact-value
                      (:entries root-type) (:exact-value candidate) refined-inner)))))))

(defn- refine-map-path-map-by-predicate
  [root-type path pred-info polarity]
  (let [key-query (first path)
        rest-path (rest path)]
    (if (seq rest-path)
      (refine-map-inner-by-predicate root-type key-query rest-path pred-info polarity)
      (refine-map-leaf-by-predicate root-type key-query pred-info polarity))))

(defn- refine-map-path-union-by-predicate
  [root-type path pred-info polarity]
  (let [refined (keep (fn [member]
                        (let [r (refine-map-path-by-predicate member path pred-info polarity)]
                          (when-not (at/bottom-type? r) r)))
                      (:members root-type))]
    (if (empty? refined)
      (at/BottomType (ato/derive-prov root-type))
      (ato/union refined))))

(defn- refine-map-path-maybe-by-predicate
  [root-type path pred-info polarity]
  (let [refined-inner (refine-map-path-by-predicate (:inner root-type) path pred-info polarity)]
    (if (at/bottom-type? refined-inner)
      (at/BottomType (ato/derive-prov root-type))
      (at/->MaybeT (ato/derive-prov root-type) refined-inner))))

(defn refine-map-path-by-predicate
  "Refine `root-type` by asserting that the value at the nested path
   `path` (non-empty vector of key-queries) satisfies the predicate `pred-info`
   with the given `polarity`. `pred-info` is `{:pred kw :class cls-or-nil}`.
   Returns the refined type."
  [root-type path pred-info polarity]
  (let [root-type (as-type root-type)]
    (cond
      (at/map-type? root-type)     (refine-map-path-map-by-predicate root-type path pred-info polarity)
      (at/union-type? root-type)   (refine-map-path-union-by-predicate root-type path pred-info polarity)
      (at/maybe-type? root-type)   (refine-map-path-maybe-by-predicate root-type path pred-info polarity)
      (at/conditional-type? root-type) root-type
      :else root-type)))

(defn- map-type-at-path-inner
  [root-type path]
  (cond
    (at/map-type? root-type)
    (let [key-query (first path)
          candidates (map-lookup-candidates (:entries root-type) key-query)
          val-type (candidate-value-type candidates)]
      (when val-type
        (map-type-at-path val-type (rest path))))

    (at/maybe-type? root-type)
    (map-type-at-path (:inner root-type) path)

    (at/union-type? root-type)
    (let [results (keep #(map-type-at-path % path) (:members root-type))]
      (when (seq results)
        (ato/union results)))

    :else nil))

(defn map-type-at-path
  "Return the Type stored at `path` within `root-type`, descending through
   map/union/maybe layers. Returns nil when the path cannot be resolved."
  [root-type path]
  (let [root-type (as-type root-type)]
    (if (empty? path)
      root-type
      (map-type-at-path-inner root-type path))))
