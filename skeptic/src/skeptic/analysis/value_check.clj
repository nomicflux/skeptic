(ns skeptic.analysis.value-check
  (:require [schema.core :as s]
            [skeptic.analysis.cast.schema :as csch]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats])
  (:import [java.lang Number Object]))

(declare value-satisfies-type?)

(defn- check-cast'
  ([a b] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b))
  ([a b opts] ((requiring-resolve 'skeptic.analysis.cast/check-cast) a b opts)))

(defn- as-type
  [value]
  (ato/normalize value))

(s/defn exact-value-type? :- s/Bool
  [type :- s/Any]
  (at/value-type? (as-type type)))

(s/defn path-key
  [type :- s/Any]
  (let [type (ascs/optional-key-inner type)]
    (when (exact-value-type? type)
      (:value type))))

(s/defn with-map-path :- csch/CastResult
  [cast-result :- csch/CastResult key :- s/Any]
  (if-let [path-value (path-key key)]
    (ascs/with-cast-path cast-result
      {:kind :map-key
       :key path-value})
    cast-result))

(s/defn map-contains-key-classification
  [type :- s/Any key :- s/Any]
  (let [descriptor (amo/map-entry-descriptor (:entries (as-type type)))
        exact-entry (amo/exact-key-entry descriptor key)]
    (if exact-entry
      (if (= :required-explicit (:kind exact-entry))
        :always
        :unknown)
      (if (seq (amo/exact-key-candidates descriptor key))
        :unknown
        :never))))

(s/defn contains-key-type-classification
  [type :- s/Any key :- s/Any]
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

      (at/conditional-type? type)
      (let [classifications (set (map #(contains-key-type-classification % key)
                                      (map second (:branches type))))]
        (cond
          (= #{:always} classifications) :always
          (= #{:never} classifications) :never
          :else :unknown))

      (at/map-type? type)
      (map-contains-key-classification type key)

      :else
      :unknown)))

(s/defn refine-type-by-contains-key :- ats/SemanticType
  [type :- ats/SemanticType key :- s/Any polarity :- s/Any]
  (let [type (as-type type)
        branches (cond
                   (at/union-type? type) (:members type)
                   (at/conditional-type? type) (map second (:branches type))
                   :else #{type})
        kept (->> branches
                  (keep (fn [branch]
                          (let [classification (contains-key-type-classification branch key)]
                            (case [polarity classification]
                              [true :never] nil
                              [false :always] nil
                              branch))))
                  set)]
    (cond
      (empty? kept) (ato/bottom type)
      (= 1 (count kept)) (first kept)
      :else (ato/union kept))))

(def integral-ground-classes
  #{Long Integer Short Byte java.math.BigInteger clojure.lang.BigInt})

(defn- numeric-ground-class
  [type]
  (let [ground (:ground (as-type type))]
    (when (and (map? ground) (:class ground))
      (:class ground))))

(s/defn numeric-ground-type? :- s/Bool
  [type :- s/Any]
  (let [type (as-type type)
        ground (:ground type)
        klass (numeric-ground-class type)]
    (or (= ground :int)
        (and klass
             (class? klass)
             (or (isa? klass Number)
                 (= klass Number)
                 (= klass java.lang.Number))))))

(s/defn non-int-numeric-ground-type? :- s/Bool
  [type :- s/Any]
  (let [klass (numeric-ground-class type)]
    (and (numeric-ground-type? type)
         (not= :int (:ground (as-type type)))
         (or (nil? klass)
             (not (contains? integral-ground-classes klass))))))

(s/defn numeric-leaf-type? :- s/Bool
  [type :- s/Any]
  (let [type (as-type type)]
    (or (at/numeric-dyn-type? type)
        (numeric-ground-type? type)
        (and (at/value-type? type) (number? (:value type))))))

(s/defn ground-accepts-value? :- s/Bool
  [type :- s/Any value :- s/Any]
  (let [ground (:ground (as-type type))]
    (cond
      (= ground :int) (integer? value)
      (= ground :str) (string? value)
      (= ground :keyword) (keyword? value)
      (= ground :symbol) (symbol? value)
      (= ground :bool) (boolean? value)
      (and (map? ground) (:class ground)) (instance? (:class ground) value)
      :else false)))

(s/defn leaf-overlap? :- s/Bool
  [source-type :- s/Any target-type :- s/Any]
  (let [source-type (as-type source-type)
        target-type (as-type target-type)]
    (cond
      (at/numeric-dyn-type? source-type)
      (cond
        (at/numeric-dyn-type? target-type) true
        (at/refinement-type? target-type) (leaf-overlap? source-type (:base target-type))
        (at/adapter-leaf-type? target-type) true
        :else (numeric-leaf-type? target-type))

      (at/numeric-dyn-type? target-type)
      (cond
        (at/refinement-type? source-type) (leaf-overlap? (:base source-type) target-type)
        (at/adapter-leaf-type? source-type) true
        :else (numeric-leaf-type? source-type))

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

(s/defn type-compatible-map-value? :- s/Bool
  [value-type :- s/Any expected-type :- s/Any]
  ((requiring-resolve 'skeptic.analysis.cast.result/ok?) (check-cast' value-type expected-type)))

(s/defn set-value-satisfies-type? :- s/Bool
  [value :- s/Any members :- s/Any]
  (and (set? value)
       (= (count value) (count members))
       (every? (fn [member-value]
                 (some #(value-satisfies-type? member-value %) members))
               value)))

(s/defn map-value-satisfies-type? :- s/Bool
  [value :- s/Any map-type :- s/Any]
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

(s/defn value-satisfies-type? :- s/Bool
  [value :- s/Any type :- s/Any]
  (let [type (as-type type)]
    (cond
      (or (at/dyn-type? type)
          (at/placeholder-type? type)
          (at/inf-cycle-type? type))
      true

      (at/numeric-dyn-type? type)
      (number? value)

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
      (boolean (some #(value-satisfies-type? value %) (:members type)))

      (at/conditional-type? type)
      (boolean
       (some (fn [[pred branch-t _]]
               (and (try (pred value) (catch Exception _ false))
                    (value-satisfies-type? value branch-t)))
             (:branches type)))

      (at/intersection-type? type)
      (every? #(value-satisfies-type? value %) (:members type))

      (at/map-type? type)
      (map-value-satisfies-type? value type)

      (at/vector-type? type)
      (and (vector? value)
           (if (:homogeneous? type)
             (every? #(value-satisfies-type? % (or (first (:items type)) (ato/dyn type)))
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
