(ns skeptic.analysis.map-ops
  (:require [clojure.set :as set]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b opts)))

(defn- value-satisfies-type?'
  [value type]
  ((requiring-resolve 'skeptic.analysis.value-check/value-satisfies-type?) value type))

(defn- leaf-overlap?'
  [source-type target-type]
  ((requiring-resolve 'skeptic.analysis.value-check/leaf-overlap?) source-type target-type))

(defn- as-type
  [value]
  (ato/normalize-type value))

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
  ([_type value]
   (exact-key-query nil value nil))
  ([_type value source-form]
   {map-key-query-tag true
    :kind :exact
    :value value
    :source-form source-form}))

(defn domain-key-query
  ([type]
   (domain-key-query type nil))
  ([type source-form]
   {map-key-query-tag true
    :kind :domain
    :type (ato/coerce-boundary-type type)
    :source-form source-form}))

(defn exact-key-query?
  [query]
  (and (map-key-query? query)
       (= :exact (:kind query))))

(defn map-key-query
  ([key]
   (map-key-query key nil))
  ([key source-form]
   (let [key (if (map-key-query? key)
               key
               (ato/coerce-boundary-type key))]
     (cond
       (map-key-query? key)
       (if (exact-key-query? key)
         {map-key-query-tag true
          :kind :exact
          :value (:value key)
          :source-form (:source-form key)}
         (domain-key-query (:type key) (:source-form key)))

       :else
       (let [exact-values (finite-exact-key-values key)]
         (if (and exact-values
                  (= 1 (count exact-values)))
           (exact-key-query nil (first exact-values) source-form)
           (domain-key-query key source-form)))))))

(defn query-key-type
  [query]
  (if (exact-key-query? query)
    (ato/exact-value-type (:value query))
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
                (reduce (fn [desc exact-value]
                          (add-descriptor-entry
                           desc
                           (descriptor-entry (ato/exact-value-type exact-value)
                                             entry-value
                                             (exact-entry-kind key-type))))
                        descriptor
                        exact-values)
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
      (:ok? (check-cast' source-key target-key)))))

(defn key-domain-overlap?
  [source-key target-key]
  (let [source-key (ascs/optional-key-inner (as-type source-key))
        target-key (ascs/optional-key-inner (as-type target-key))]
    (cond
      (or (at/dyn-type? source-key)
          (at/dyn-type? target-key)
          (at/placeholder-type? source-key)
          (at/placeholder-type? target-key))
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
      (or (:ok? (check-cast' source-key target-key))
          (:ok? (check-cast' target-key source-key))
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
  (let [descriptor (map-entry-descriptor entries)
        key-query (map-key-query key-query)]
    (if (exact-key-query? key-query)
      (exact-key-candidates descriptor (:value key-query))
      (domain-key-candidates descriptor (query-key-type key-query)))))

(def no-default ::no-default)

(defn candidate-value-type
  [candidates]
  (when (seq candidates)
    (ato/union-type (map :value candidates))))

(defn map-get-type
  ([m key]
   (map-get-type m key no-default))
  ([m key default]
   (let [m (as-type m)
         key-query (map-key-query key)
         default-provided? (not= default no-default)
         default-type (when default-provided?
                        (as-type default))]
     (cond
       (at/maybe-type? m)
       (ato/union-type
        [(map-get-type (:inner m) key-query default)
         (or default-type (at/->MaybeT at/Dyn))])

       (at/union-type? m)
       (ato/union-type (map #(map-get-type % key-query default) (:members m)))

       (at/map-type? m)
       (if-let [candidates (seq (map-lookup-candidates (:entries m) key-query))]
         (let [base-value (candidate-value-type candidates)
               base-value (if (and (exact-key-query? key-query)
                                   (= 1 (count candidates))
                                   (= :optional-explicit (:kind (first candidates)))
                                   (not default-provided?))
                            (at/->MaybeT base-value)
                            base-value)]
           (if default-provided?
             (ato/union-type [base-value default-type])
             base-value))
         (if default-provided?
           default-type
           at/Dyn))

       :else
       (if default-provided?
         (ato/union-type [at/Dyn default-type])
         at/Dyn)))))

(defn merge-map-types
  [types]
  (let [types (mapv as-type types)]
    (if (every? at/map-type? types)
      (at/->MapT (apply merge (map :entries types)))
      at/Dyn)))
