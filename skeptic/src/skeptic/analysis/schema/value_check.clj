(ns skeptic.analysis.schema.value-check
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema.cast-support :as ascs]
            [skeptic.analysis.schema.map-ops :as asm]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(declare value-satisfies-type?)

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.schema/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.schema/check-cast) a b opts)))

(defn exact-value-type?
  [type]
  (at/value-type? (ab/schema->type type)))

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
       (let [entry-type (ab/schema->type entry-key)
             inner (ascs/optional-key-inner entry-type)]
         (cond
           (and (at/optional-key-type? entry-type)
                (exact-value-type? inner))
           :optional-explicit

           (exact-value-type? inner)
           :required-explicit

           (and (at/optional-key-type? entry-type)
                (asm/finite-exact-key-values inner))
           :optional-explicit

           (asm/finite-exact-key-values inner)
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

(defn path-key
  [type]
  (let [type (ascs/optional-key-inner type)]
    (when (exact-value-type? type)
      (:value type))))

(defn with-map-path
  [cast-result key]
  (if-let [path-value (path-key key)]
    (ascs/with-cast-path cast-result
      {:kind :map-key
       :key path-value})
    cast-result))

(defn map-contains-key-classification
  [type key]
  (let [descriptor (asm/map-entry-descriptor (:entries (ab/schema->type type)))
        exact-entry (asm/exact-key-entry descriptor key)]
    (if exact-entry
      (if (= :required-explicit (:kind exact-entry))
        :always
        :unknown)
      (if (seq (asm/exact-key-candidates descriptor key))
        :unknown
        :never))))

(defn contains-key-classification
  [schema key]
  (let [type (ab/schema->type schema)]
    (cond
      (at/bottom-type? type) :never

      (at/maybe-type? type)
      (case (contains-key-classification (:inner type) key)
        :never :never
        :always :unknown
        :unknown :unknown)

      (at/map-type? type)
      (map-contains-key-classification type key)

      :else
      :unknown)))

(defn contains-key-type-classification
  [type key]
  (let [type (ab/schema->type type)]
    (if (at/union-type? type)
      (let [classifications (set (map #(contains-key-type-classification % key)
                                      (:members type)))]
        (cond
          (= #{:always} classifications) :always
          (= #{:never} classifications) :never
          :else :unknown))
      (contains-key-classification type key))))

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

(defn refine-type-by-contains-key
  [type key polarity]
  (let [type (ab/schema->type type)
        branches (if (at/union-type? type)
                   (:members type)
                   #{type})
        kept (->> branches
                  (keep (fn [branch]
                          (let [classification (contains-key-type-classification branch key)]
                            (case [polarity classification]
                              [true :never] nil
                              [false :always] nil
                              branch))))
                  set)]
    (cond
      (empty? kept) at/BottomType
      (= 1 (count kept)) (first kept)
      :else (ab/union-type kept))))

(defn ground-accepts-value?
  [type value]
  (let [ground (:ground (ab/schema->type type))]
    (cond
      (= ground :int) (integer? value)
      (= ground :str) (string? value)
      (= ground :keyword) (keyword? value)
      (= ground :symbol) (symbol? value)
      (= ground :bool) (boolean? value)
      (and (map? ground) (:class ground)) (instance? (:class ground) value)
      :else false)))

(defn leaf-overlap?
  [source-type target-type]
  (let [source-type (ab/schema->type source-type)
        target-type (ab/schema->type target-type)]
    (cond
      (at/ground-type? source-type)
      (cond
        (at/ground-type? target-type)
        (let [s (:ground source-type)
              t (:ground target-type)]
          (cond
            (= s t) true
            (and (map? s) (:class s) (map? t) (:class t))
            (or (.isAssignableFrom ^Class (:class s) ^Class (:class t))
                (.isAssignableFrom ^Class (:class t) ^Class (:class s)))
            :else false))

        (at/refinement-type? target-type)
        (leaf-overlap? source-type (:base target-type))

        (at/adapter-leaf-type? target-type)
        true

        :else false)

      (at/refinement-type? source-type)
      (leaf-overlap? (:base source-type) target-type)

      (at/adapter-leaf-type? source-type)
      true

      :else false)))

(defn type-compatible-map-value?
  [value-type expected-type]
  (:ok? (check-cast' value-type expected-type)))

(defn set-value-satisfies-type?
  [value members]
  (and (set? value)
       (= (count value) (count members))
       (every? (fn [member-value]
                 (some #(value-satisfies-type? member-value %) members))
               value)))

(defn map-value-satisfies-type?
  [value map-type]
  (and (map? value)
       (let [descriptor (asm/map-entry-descriptor (:entries (ab/schema->type map-type)))
             required-missing (atom (set (keys (:required-exact descriptor))))]
         (and
          (every? (fn [[k v]]
                    (if-let [exact-entry (asm/exact-key-entry descriptor k)]
                      (do
                        (swap! required-missing disj k)
                        (value-satisfies-type? v (:value exact-entry)))
                      (let [candidates (asm/exact-key-candidates descriptor k)]
                        (and (seq candidates)
                             (some #(value-satisfies-type? v (:value %))
                                   candidates)))))
                  value)
          (empty? @required-missing)))))

(defn value-satisfies-type?
  [value type]
  (let [type (ab/schema->type type)]
    (cond
      (or (at/dyn-type? type)
          (at/placeholder-type? type))
      true

      (at/bottom-type? type)
      true

      (at/value-type? type)
      (= value (:value type))

      (at/ground-type? type)
      (ground-accepts-value? type value)

      (at/refinement-type? type)
      (and (value-satisfies-type? value (:base type))
           ((:accepts? type) value))

      (at/adapter-leaf-type? type)
      ((:accepts? type) value)

      (at/optional-key-type? type)
      (value-satisfies-type? value (:inner type))

      (at/maybe-type? type)
      (or (nil? value)
          (value-satisfies-type? value (:inner type)))

      (at/union-type? type)
      (some #(value-satisfies-type? value %) (:members type))

      (at/intersection-type? type)
      (every? #(value-satisfies-type? value %) (:members type))

      (at/map-type? type)
      (map-value-satisfies-type? value type)

      (at/vector-type? type)
      (and (vector? value)
           (if (:homogeneous? type)
             (every? #(value-satisfies-type? % (or (first (:items type)) at/Dyn))
                     value)
             (and (= (count value) (count (:items type)))
                  (every? true? (map value-satisfies-type? value (:items type))))))

      (at/seq-type? type)
      (and (sequential? value)
           (= (count value) (count (:items type)))
           (every? true? (map value-satisfies-type? value (:items type))))

      (at/set-type? type)
      (set-value-satisfies-type? value (:members type))

      (at/var-type? type)
      (and (var? value)
           (value-satisfies-type? @value (:inner type)))

      :else false)))
