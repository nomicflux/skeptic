(ns skeptic.analysis.schema.valued
  (:require [schema.core :as s]
            [skeptic.analysis.schema.map-ops :as asm]
            [skeptic.analysis.schema.value-check :as asv]
            [skeptic.analysis.schema-base :as sb]))

(defn- same-form?
  [expected actual]
  (= expected actual))

 (defn matches-map
   [expected actual-k actual-v]
   (when-let [expected-value (asm/map-get-schema expected actual-k)]
     (asm/nested-value-compatible? expected-value actual-v)))

 (defn valued-compatible?
   [expected actual]
   (cond
     (sb/valued-schema? expected)
     (throw (IllegalArgumentException. "Only actual can be a valued Plumatic Schema form"))

     (sb/valued-schema? actual)
     (let [v (:value actual)
           s (:schema actual)
           e (sb/de-maybe expected)]
       (or (same-form? e v)
           (same-form? e s)
           (same-form? e (s/optional-key v))
           (same-form? e (s/optional-key s))
           (= (sb/check-if-schema e v) sb/plumatic-valid)))

     (or (same-form? expected actual)
         (same-form? expected (s/optional-key actual))
         (= (sb/check-if-schema expected actual) sb/plumatic-valid))
     true

     (and (map? expected) (map? actual))
     (every? (fn [[k v]] (matches-map expected k v)) actual)

     :else false))

 (defn get-by-matching-schema
   [m k]
   (asm/map-get-schema m k))

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
                                  {expanded-key (if (sb/valued-schema? value)
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
