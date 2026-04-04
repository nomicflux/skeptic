(ns skeptic.analysis.schema.value-check
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn- as-type
  [value]
  (if (abc/schema? value)
    (ab/import-schema-type value)
    (ato/normalize-type value)))

(defn map-entry-kind
  ([entry-key]
   (let [entry-key (abc/canonicalize-schema entry-key)]
     (cond
       (and (not (at/semantic-type-value? entry-key))
            (s/optional-key? entry-key))
       :optional-explicit

       (and (not (at/semantic-type-value? entry-key))
            (s/specific-key? entry-key))
       :required-explicit

       :else
       (let [entry-type (as-type entry-key)
             inner (ascs/optional-key-inner entry-type)]
         (cond
           (and (at/optional-key-type? entry-type)
                (avc/exact-value-type? inner))
           :optional-explicit

           (avc/exact-value-type? inner)
           :required-explicit

           (and (at/optional-key-type? entry-type)
                (amo/finite-exact-key-values inner))
           :optional-explicit

           (amo/finite-exact-key-values inner)
           :required-explicit

           :else
           :extra-schema)))))
  ([entries entry-key]
   (let [entries (abc/canonicalize-schema entries)
         entry-key (abc/canonicalize-schema entry-key)
         typed-entries? (every? at/semantic-type-value? (keys entries))]
     (if (and (sb/plain-map-schema? entries)
              (not typed-entries?))
       (let [extra-key (s/find-extra-keys-schema entries)]
         (if (= entry-key extra-key)
           :extra-schema
           (map-entry-kind entry-key)))
       (map-entry-kind entry-key)))))

(defn contains-key-classification
  [schema key]
  (let [type (as-type schema)]
    (cond
      (at/bottom-type? type) :never

      (at/maybe-type? type)
      (case (contains-key-classification (:inner type) key)
        :never :never
        :always :unknown
        :unknown :unknown)

      (at/map-type? type)
      (avc/map-contains-key-classification type key)

      :else
      :unknown)))

(defn refine-schema-by-contains-key
  [schema key polarity]
  (let [schema (abc/canonicalize-schema schema)
        branches (or (abc/union-like-branches schema) #{schema})
        kept (->> branches
                  (keep (fn [branch]
                          (let [classification (contains-key-classification branch key)]
                            (case [polarity classification]
                              [true :never] nil
                              [false :always] nil
                              branch))))
                  set)]
    (cond
      (empty? kept) sb/Bottom
      (= 1 (count kept)) (first kept)
      :else (abc/schema-join kept))))
