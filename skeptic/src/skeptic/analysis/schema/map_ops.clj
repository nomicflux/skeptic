(ns skeptic.analysis.schema.map-ops
  (:require [clojure.set :as set]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.schema.cast-support :as ascs]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.schema/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.schema/check-cast) a b opts)))

(defn- value-satisfies-type?'
  [value type]
  ((requiring-resolve 'skeptic.analysis.schema.value-check/value-satisfies-type?) value type))

(defn- map-entry-kind'
  ([entry-key]
   ((requiring-resolve 'skeptic.analysis.schema.value-check/map-entry-kind) entry-key))
  ([entries entry-key]
   ((requiring-resolve 'skeptic.analysis.schema.value-check/map-entry-kind) entries entry-key)))

(defn- schema-compatible-via-cast?
  [expected actual]
  (:ok? (check-cast' (ab/schema->type actual) (ab/schema->type expected))))

(defn finite-exact-key-values
  [type]
  (let [type (ascs/optional-key-inner (ab/schema->type type))]
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
  ([schema value]
   (exact-key-query schema value nil))
  ([schema value source-form]
   {map-key-query-tag true
    :kind :exact
    :schema (abc/canonicalize-schema schema)
    :value value
    :source-form source-form}))

(defn domain-key-query
  ([schema]
   (domain-key-query schema nil))
  ([schema source-form]
   {map-key-query-tag true
    :kind :domain
    :schema (abc/canonicalize-schema schema)
    :source-form source-form}))

(defn exact-key-query?
  [query]
  (and (map-key-query? query)
       (= :exact (:kind query))))

(defn map-key-query
  ([key]
   (map-key-query key nil))
  ([key source-form]
   (let [key (abl/localize-schema-value key)]
     (cond
       (map-key-query? key)
       (update key :schema abc/canonicalize-schema)

       (sb/valued-schema? key)
       (exact-key-query (:schema key) (:value key) (:value key))

       :else
       (let [exact-values (finite-exact-key-values key)]
         (if (and exact-values
                  (= 1 (count exact-values)))
           (exact-key-query key (first exact-values) source-form)
           (domain-key-query key source-form)))))))

(defn query-key-type
  [query]
  (if (exact-key-query? query)
    (ab/exact-value-import-type (:value query))
    (ab/schema->type (:schema query))))

(defn exact-entry-kind
  [key-type]
  (if (at/optional-key-type? key-type)
    :optional-explicit
    :required-explicit))

(defn descriptor-entry
  [entry-key entry-value kind]
  (let [entry-key (abc/canonicalize-schema entry-key)
        entry-value (abc/canonicalize-schema entry-value)
        key-type (ab/schema->type entry-key)
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
      (update descriptor :schema-entries conj entry))
    (update descriptor :schema-entries conj entry)))

(defn map-entry-descriptor
  [entries]
  (let [entries (abc/canonicalize-schema entries)]
    (reduce (fn [descriptor [entry-key entry-value]]
              (let [entry-key (abc/canonicalize-schema entry-key)
                    entry-value (abc/canonicalize-schema entry-value)
                    key-type (ab/schema->type entry-key)]
                (if-let [exact-values (finite-exact-key-values key-type)]
                  (reduce (fn [desc exact-value]
                            (add-descriptor-entry
                              desc
                              (descriptor-entry (ab/exact-value-import-type exact-value)
                                                entry-value
                                                (exact-entry-kind key-type))))
                          descriptor
                          exact-values)
                  (add-descriptor-entry
                    descriptor
                    (descriptor-entry entry-key
                                      entry-value
                                      (map-entry-kind' entries entry-key))))))
            {:entries entries
             :required-exact {}
             :optional-exact {}
             :schema-entries []}
            entries)))

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
  (let [source-key (ascs/optional-key-inner (ab/schema->type source-key))
        target-key (ascs/optional-key-inner (ab/schema->type target-key))]
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
  (let [source-key (ascs/optional-key-inner (ab/schema->type source-key))
        target-key (ascs/optional-key-inner (ab/schema->type target-key))]
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
          ((requiring-resolve 'skeptic.analysis.schema.value-check/leaf-overlap?) source-key target-key)))))

(defn exact-key-candidates
  [descriptor exact-value]
  (if-let [entry (exact-key-entry descriptor exact-value)]
    [entry]
    (->> (:schema-entries descriptor)
         (filter #(value-satisfies-type?' exact-value (:inner-key-type %)))
         vec)))

(defn domain-key-candidates
  [descriptor key-type]
  (let [key-type (ascs/optional-key-inner (ab/schema->type key-type))]
    (vec
      (concat
        (filter #(value-satisfies-type?' (:exact-value %) key-type)
                (effective-exact-entries descriptor))
        (filter #(key-domain-overlap? key-type (:inner-key-type %))
                (:schema-entries descriptor))))))

(defn map-lookup-candidates
  [entries key-query]
  (let [descriptor (map-entry-descriptor entries)
        key-query (map-key-query key-query)]
    (if (exact-key-query? key-query)
      (exact-key-candidates descriptor (:value key-query))
      (domain-key-candidates descriptor (query-key-type key-query)))))

(defn candidate-value-schema
  [candidates]
  (when (seq candidates)
    (abc/schema-join (set (map (comp abc/semantic-value-schema :value) candidates)))))

(defn nested-value-compatible?
  [expected actual]
  (let [actual (abc/canonicalize-schema actual)]
    (if (sb/valued-schema? actual)
      (or (schema-compatible-via-cast? expected (:value actual))
          (schema-compatible-via-cast? expected (:schema actual)))
      (schema-compatible-via-cast? expected actual))))

(def no-default ::no-default)

(defn map-get-schema
  ([m key]
   (map-get-schema m key no-default))
  ([m key default]
   (let [m (abc/canonicalize-schema m)
         key-query (map-key-query key)
         default-provided? (not= default no-default)
         default-schema (when default-provided?
                          (abc/canonicalize-schema default))]
     (cond
       (sb/maybe? m)
       (abc/schema-join
        [(map-get-schema (sb/de-maybe m) key-query default)
         (or default-schema (s/maybe s/Any))])

       (sb/join? m)
       (abc/schema-join (set (map #(map-get-schema % key-query default) (:schemas m))))

       (sb/plain-map-schema? m)
       (if-let [candidates (seq (map-lookup-candidates m key-query))]
         (let [base-value (candidate-value-schema candidates)
               base-value (if (and (exact-key-query? key-query)
                                   (= 1 (count candidates))
                                   (= :optional-explicit (:kind (first candidates)))
                                   (not default-provided?))
                            (abc/maybe-schema base-value)
                            base-value)]
           (if default-provided?
             (abc/schema-join [base-value default-schema])
             base-value))
         (if default-provided?
           default-schema
           s/Any))

       :else
       (if default-provided?
         (abc/schema-join [s/Any default-schema])
         s/Any)))))

(defn candidate-value-type
  [candidates]
  (when (seq candidates)
    (ab/union-type (map :value candidates))))

(defn map-get-type
  ([m key]
   (map-get-type m key no-default))
  ([m key default]
   (let [m (ab/schema->type m)
         key-query (map-key-query key)
         default-provided? (not= default no-default)
         default-type (when default-provided?
                        (ab/schema->type default))]
     (cond
       (at/maybe-type? m)
       (ab/union-type
        [(map-get-type (:inner m) key-query default)
         (or default-type (at/->MaybeT at/Dyn))])

       (at/union-type? m)
       (ab/union-type (map #(map-get-type % key-query default) (:members m)))

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
             (ab/union-type [base-value default-type])
             base-value))
         (if default-provided?
           default-type
           at/Dyn))

       :else
       (if default-provided?
         (ab/union-type [at/Dyn default-type])
         at/Dyn)))))

(defn merge-map-schemas
  [schemas]
  (let [schemas (mapv abc/canonicalize-schema schemas)]
    (if (every? sb/plain-map-schema? schemas)
      (reduce merge {} schemas)
      s/Any)))

(defn merge-map-types
  [types]
  (let [types (mapv ab/schema->type types)]
    (if (every? at/map-type? types)
      (at/->MapT (apply merge (map :entries types)))
      at/Dyn)))
