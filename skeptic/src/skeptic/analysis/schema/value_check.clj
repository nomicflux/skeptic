(ns skeptic.analysis.schema.value-check
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]))

(defn map-entry-kind
  ([entry-key]
   (let [entry-key (abc/canonicalize-schema entry-key)]
     (cond
       (s/optional-key? entry-key)
       :optional-explicit

       (or (sb/schema-literal? entry-key)
           (s/specific-key? entry-key))
       :required-explicit

       :else :domain-entry)))
  ([entries entry-key]
   (let [entries (abc/canonicalize-schema entries)
         entry-key (abc/canonicalize-schema entry-key)]
     (if (sb/plain-map-schema? entries)
       (let [extra-key (s/find-extra-keys-schema entries)]
         (if (= entry-key extra-key)
           :domain-entry
           (map-entry-kind entry-key)))
       (map-entry-kind entry-key)))))

(defn contains-key-classification
  [schema key]
  (let [schema (abc/canonicalize-schema schema)
        key (abc/canonicalize-schema key)]
    (cond
      (= schema sb/Bottom) :never

      (sb/maybe? schema)
      (case (contains-key-classification (:schema schema) key)
        :never :never
        :always :unknown
        :unknown :unknown)

      (sb/plain-map-schema? schema)
      (if (or (contains? schema key)
              (contains? schema (s/optional-key key)))
        (if (= :required-explicit (map-entry-kind schema key))
          :always
          :unknown)
        (if (some (fn [[entry-key _entry-value]]
                    (and (= :domain-entry (map-entry-kind schema entry-key))
                         (= (sb/check-if-schema entry-key key) sb/plumatic-valid)))
                  schema)
          :unknown
          :never))

      (abc/union-like-branches schema)
      (let [classifications (set (map #(contains-key-classification % key)
                                      (abc/union-like-branches schema)))]
        (cond
          (= #{:always} classifications) :always
          (= #{:never} classifications) :never
          :else :unknown))

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
