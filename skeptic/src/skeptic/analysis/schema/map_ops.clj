(ns skeptic.analysis.schema.map-ops
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.schema.value-check :as asv]))

(defn- same-form?
  [expected actual]
  (= (abc/canonicalize-schema expected)
     (abc/canonicalize-schema actual)))

(defn- lookup-key
  [key]
  (if (sb/valued-schema? key)
    (:value key)
    key))

(defn- candidate-value-schema
  [candidates]
  (when (seq candidates)
    (abc/schema-join (set (map (comp abc/semantic-value-schema second) candidates)))))

(defn- exact-entry-key?
  [entry-key]
  (not= :domain-entry (asv/map-entry-kind entry-key)))

(defn- exact-entry-match?
  [entry-key key]
  (or (same-form? entry-key key)
      (and (s/optional-key? entry-key)
           (same-form? (:k entry-key) key))))

(defn- domain-entry-match?
  [entry-key key]
  (or (same-form? entry-key key)
      (= (sb/check-if-schema entry-key key) sb/plumatic-valid)))

(defn- candidate-entries
  [entries key]
  (let [key (abc/canonicalize-schema (lookup-key key))
        exact-candidates (->> entries
                              (filter (fn [[entry-key _entry-value]]
                                        (and (exact-entry-key? entry-key)
                                             (exact-entry-match? entry-key key))))
                              vec)]
    (if (seq exact-candidates)
      exact-candidates
      (->> entries
           (filter (fn [[entry-key _entry-value]]
                     (and (= :domain-entry (asv/map-entry-kind entries entry-key))
                          (domain-entry-match? entry-key key))))
           vec))))

(defn nested-value-compatible?
  [expected actual]
  (let [actual (abc/canonicalize-schema actual)]
    (if (sb/valued-schema? actual)
      (or (nested-value-compatible? expected (:value actual))
          (nested-value-compatible? expected (:schema actual)))
      (or (same-form? expected actual)
          (same-form? expected (s/optional-key actual))
          (= (sb/check-if-schema expected actual) sb/plumatic-valid)))))

(def no-default ::no-default)

(defn map-get-schema
  ([m key]
   (map-get-schema m key no-default))
  ([m key default]
   (let [m (abc/canonicalize-schema m)
         default-provided? (not= default no-default)
         default-schema (when default-provided?
                          (abc/canonicalize-schema default))]
     (cond
       (sb/maybe? m)
       (abc/schema-join
        [(map-get-schema (sb/de-maybe m) key default)
         (or default-schema (s/maybe s/Any))])

       (sb/join? m)
       (abc/schema-join (set (map #(map-get-schema % key default) (:schemas m))))

       (sb/plain-map-schema? m)
       (if-let [candidates (seq (candidate-entries m key))]
         (let [base-value (candidate-value-schema candidates)
               base-value (if (and (= 1 (count candidates))
                                   (= :optional-explicit (asv/map-entry-kind m (ffirst candidates)))
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
