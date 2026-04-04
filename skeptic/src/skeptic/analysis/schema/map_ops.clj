(ns skeptic.analysis.schema.map-ops
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.schema/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.schema/check-cast) a b opts)))

(defn- schema-compatible-via-cast?
  [expected actual]
  (:ok? (check-cast' (ab/schema->type actual) (ab/schema->type expected))))

(defn- map-entry-kind'
  ([entry-key]
   ((requiring-resolve 'skeptic.analysis.schema.value-check/map-entry-kind) entry-key))
  ([entries entry-key]
   ((requiring-resolve 'skeptic.analysis.schema.value-check/map-entry-kind) entries entry-key)))

(defn- candidate-value-schema
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

(defn- schema-key-query
  [key]
  (cond
    (amo/map-key-query? key)
    (amo/map-key-query key)

    (sb/valued-schema? key)
    (amo/exact-key-query (ab/schema->type (:schema key))
                         (:value key)
                         (:value key))

    :else
    (amo/map-key-query (ab/schema->type key))))

(defn- schema-descriptor-entry
  [entry-key entry-value kind]
  (let [key-type (if (at/semantic-type-value? entry-key)
                   (ato/normalize-type entry-key)
                   (ab/schema->type entry-key))
        inner-key-type (ascs/optional-key-inner key-type)]
    {:key entry-key
     :value entry-value
     :kind kind
     :key-type key-type
     :inner-key-type inner-key-type
     :exact-value (when (at/value-type? inner-key-type)
                    (:value inner-key-type))}))

(defn- add-schema-descriptor-entry
  [descriptor entry]
  (if-let [exact-value (:exact-value entry)]
    (case (:kind entry)
      :required-explicit (assoc-in descriptor [:required-exact exact-value] entry)
      :optional-explicit (assoc-in descriptor [:optional-exact exact-value] entry)
      (update descriptor :schema-entries conj entry))
    (update descriptor :schema-entries conj entry)))

(defn- schema-map-entry-descriptor
  [entries]
  (let [entries (abc/canonicalize-schema entries)]
    (reduce (fn [descriptor [entry-key entry-value]]
              (let [entry-key (abc/canonicalize-schema entry-key)
                    entry-value (abc/canonicalize-schema entry-value)
                    key-type (ab/schema->type entry-key)]
                (if-let [exact-values (amo/finite-exact-key-values key-type)]
                  (reduce (fn [desc exact-value]
                            (add-schema-descriptor-entry
                             desc
                             (schema-descriptor-entry (ato/exact-value-type exact-value)
                                                      entry-value
                                                      (amo/exact-entry-kind key-type))))
                          descriptor
                          exact-values)
                  (add-schema-descriptor-entry
                   descriptor
                   (schema-descriptor-entry entry-key
                                            entry-value
                                            (map-entry-kind' entries entry-key))))))
            {:entries entries
             :required-exact {}
             :optional-exact {}
             :schema-entries []}
            entries)))

(defn- schema-effective-exact-entries
  [descriptor]
  (concat (vals (:required-exact descriptor))
          (->> (:optional-exact descriptor)
               (remove (fn [[value _entry]]
                         (contains? (:required-exact descriptor) value)))
               (map val))))

(defn- schema-exact-key-entry
  [descriptor exact-value]
  (or (get-in descriptor [:required-exact exact-value])
      (get-in descriptor [:optional-exact exact-value])))

(defn- schema-exact-key-candidates
  [descriptor exact-value]
  (if-let [entry (schema-exact-key-entry descriptor exact-value)]
    [entry]
    (->> (:schema-entries descriptor)
         (filter #(:ok? (check-cast' (ato/exact-value-type exact-value)
                                     (:inner-key-type %))))
         vec)))

(defn- schema-domain-key-candidates
  [descriptor query]
  (let [key-type (amo/query-key-type query)]
    (vec
     (concat
      (filter #(:ok? (check-cast' (ato/exact-value-type (:exact-value %))
                                  key-type))
              (schema-effective-exact-entries descriptor))
      (filter #(amo/key-domain-overlap? key-type (:inner-key-type %))
              (:schema-entries descriptor))))))

(defn map-get-schema
  ([m key]
   (map-get-schema m key no-default))
  ([m key default]
   (let [m (abc/canonicalize-schema m)
         key-query (schema-key-query key)
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
       (if-let [candidates (seq (let [descriptor (schema-map-entry-descriptor m)]
                                  (if (amo/exact-key-query? key-query)
                                    (schema-exact-key-candidates descriptor (:value key-query))
                                    (schema-domain-key-candidates descriptor key-query))))]
         (let [base-value (candidate-value-schema candidates)
               base-value (if (and (amo/exact-key-query? key-query)
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

(defn merge-map-schemas
  [schemas]
  (let [schemas (mapv abc/canonicalize-schema schemas)]
    (if (every? sb/plain-map-schema? schemas)
      (reduce merge {} schemas)
      s/Any)))
