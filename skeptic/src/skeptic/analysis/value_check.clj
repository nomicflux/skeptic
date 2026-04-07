(ns skeptic.analysis.value-check
  (:require [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at])
  (:import [java.lang Number Object]))

(declare value-satisfies-type?)

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b opts)))

(defn- as-type
  [value]
  (ato/normalize-type value))

(defn exact-value-type?
  [type]
  (at/value-type? (as-type type)))

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
  (let [descriptor (amo/map-entry-descriptor (:entries (as-type type)))
        exact-entry (amo/exact-key-entry descriptor key)]
    (if exact-entry
      (if (= :required-explicit (:kind exact-entry))
        :always
        :unknown)
      (if (seq (amo/exact-key-candidates descriptor key))
        :unknown
        :never))))

(defn contains-key-type-classification
  [type key]
  (let [type (as-type type)]
    (cond
      (at/bottom-type? type) :never

      (at/maybe-type? type)
      (case (contains-key-type-classification (:inner type) key)
        :never :never
        :always :unknown
        :unknown :unknown)

      (at/union-type? type)
      (let [classifications (set (map #(contains-key-type-classification % key)
                                      (:members type)))]
        (cond
          (= #{:always} classifications) :always
          (= #{:never} classifications) :never
          :else :unknown))

      (at/map-type? type)
      (map-contains-key-classification type key)

      :else
      :unknown)))

(defn refine-type-by-contains-key
  [type key polarity]
  (let [type (as-type type)
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
      :else (ato/union-type kept))))

(defn ground-accepts-value?
  [type value]
  (let [ground (:ground (as-type type))]
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
  (let [source-type (as-type source-type)
        target-type (as-type target-type)]
    (cond
      (at/ground-type? source-type)
      (cond
        (at/ground-type? target-type)
        (let [s (:ground source-type)
              t (:ground target-type)]
          (cond
            (= s t) true
            (and (= s :int)
                 (map? t)
                 (= Number (:class t)))
            true
            (and (map? s)
                 (= Number (:class s))
                 (= t :int))
            false
            (and (#{:int :str :keyword :symbol :bool} s)
                 (map? t)
                 (= Object (:class t)))
            true
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
       (let [descriptor (amo/map-entry-descriptor (:entries (as-type map-type)))
             required-missing (atom (set (keys (:required-exact descriptor))))]
         (and
          (every? (fn [[k v]]
                    (if-let [exact-entry (amo/exact-key-entry descriptor k)]
                      (do
                        (swap! required-missing disj k)
                        (value-satisfies-type? v (:value exact-entry)))
                      (let [candidates (amo/exact-key-candidates descriptor k)]
                        (and (seq candidates)
                             (some #(value-satisfies-type? v (:value %))
                                   candidates)))))
                  value)
          (empty? @required-missing)))))

(defn value-satisfies-type?
  [value type]
  (let [type (as-type type)]
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
