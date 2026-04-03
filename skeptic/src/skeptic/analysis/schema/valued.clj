 (ns skeptic.analysis.schema.valued
   (:require [schema.core :as s]
             [skeptic.analysis.bridge.canonicalize :as abc]
             [skeptic.analysis.schema.cast-support :as ascs]
             [skeptic.analysis.schema.map-ops :as asm]
             [skeptic.analysis.schema.value-check :as asv]
             [skeptic.analysis.schema-base :as sb]))

 (defn matches-map
   [expected actual-k actual-v]
   (let [expected (abc/canonicalize-schema expected)
         actual-v (abc/canonicalize-schema actual-v)
         descriptor (asm/map-entry-descriptor expected)
         key-query (asm/map-key-query actual-k)]
     (if (asm/exact-key-query? key-query)
       (every? (fn [exact-value]
                 (some #(asm/nested-value-compatible? (:value %) actual-v)
                       (asm/exact-key-candidates descriptor exact-value)))
               [(:value key-query)])
       (some #(asm/nested-value-compatible? (:value %) actual-v)
             (filter (fn [entry]
                       (asm/key-domain-covered? (asm/query-key-type key-query)
                                                (:inner-key-type entry)))
                     (:schema-entries descriptor))))))

 (defn valued-compatible?
   [expected actual]
   (let [expected (abc/canonicalize-schema expected)
         actual (abc/canonicalize-schema actual)]
     (cond
       (sb/valued-schema? expected)
       (throw (IllegalArgumentException. "Only actual can be a valued schema"))

       (sb/valued-schema? actual)
       (let [v (:value actual)
             s (:schema actual)
             e (sb/de-maybe expected)]
         (or (ascs/schema-equivalent? e v)
             (ascs/schema-equivalent? e s)
             (ascs/schema-equivalent? e (s/optional-key v))
             (ascs/schema-equivalent? e (s/optional-key s))
             (= (sb/check-if-schema e v) ::schema-valid)))

       (or (ascs/schema-equivalent? expected actual)
           (ascs/schema-equivalent? expected (s/optional-key actual))
           (= (sb/check-if-schema expected actual) ::schema-valid))
       true

       (and (map? expected) (map? actual))
       (every? (fn [[k v]] (matches-map expected k v)) actual)

       :else false)))

 (defn get-by-matching-schema
   [m k]
   (asm/candidate-value-schema (asm/map-lookup-candidates m (asm/map-key-query k))))

 (defn valued-get
   [m k]
   (get-by-matching-schema m k))

 (defn required-key?
   [k]
   (= :required-explicit (asv/map-entry-kind k)))

 (defn schema-values
   [schema]
   (letfn [(schema-value-key-expansions [schema valued-keys]
             (->> valued-keys
                  (map (fn [key]
                         (let [value (get schema key)]
                           (map (fn [expanded-key]
                                  {expanded-key (if (and (abc/schema? expanded-key)
                                                         (sb/valued-schema? value))
                                                  (:schema value)
                                                  value)})
                                (schema-values key)))))
                  sb/all-pairs
                  (map (partial into {}))))
           (schema-value-value-expansions [schema base-keys]
             (->> base-keys
                  (map (fn [key]
                         (let [value (get schema key)
                               expanded-values (if (sb/valued-schema? value)
                                                 (schema-values value)
                                                 [value])]
                           (map (fn [expanded-value]
                                  {key expanded-value})
                                expanded-values))))
                  sb/all-pairs
                  (map (partial into {}))))
           (merge-schema-value-expansions [value-expansions key-expansions]
             (mapcat (fn [value-schema]
                       (mapv #(merge value-schema %) key-expansions))
                     value-expansions))]
     (cond
       (sb/valued-schema? schema)
       [(:schema schema) (:value schema)]

       (and (map? schema)
            (not (s/optional-key? schema)))
       (let [{valued-schemas true base-schemas false} (->> schema keys (group-by sb/valued-schema?))
             key-expansions (schema-value-key-expansions schema valued-schemas)
             value-expansions (schema-value-value-expansions schema base-schemas)]
         (merge-schema-value-expansions value-expansions key-expansions))

       :else
       [schema])))
