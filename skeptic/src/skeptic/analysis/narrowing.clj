(ns skeptic.analysis.narrowing
  (:require [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at])
  (:import [java.lang Number]))

(declare partition-type-for-predicate*)

(def integral-ground-classes
  #{Long Integer Short Byte java.math.BigInteger clojure.lang.BigInt})

(defn- numeric-ground?
  [g]
  (or (= :int g)
      (and (map? g) (:class g)
           (let [^Class c (:class g)]
             (and (class? c)
                  (or (isa? c Number)
                      (= c Number)
                      (= c java.lang.Number)))))))

(defn- ground-matches-number?
  [t]
  (when (at/ground-type? t)
    (let [g (:ground t)]
      (or (= :int g) (numeric-ground? g)))))

(defn- instance-ground-assignable?
  [ground ^Class pred-class]
  (when (and (map? ground) (:class ground))
    (let [^Class c (:class ground)]
      (and (class? c) (.isAssignableFrom pred-class c)))))

(defn- numeric-dyn-instance-classification
  [pred-class]
  (cond
    (nil? pred-class) :unknown
    (.isAssignableFrom pred-class Number) :matches
    (.isAssignableFrom Number pred-class) :unknown
    :else :does-not-match))

(defn classify-leaf-for-predicate?
  [pred-info t]
  (let [t (ato/normalize-type t)
        pred (:pred pred-info)]
    (cond
      (at/bottom-type? t) :matches
      (at/dyn-type? t) :unknown
      (at/placeholder-type? t) :unknown
      (at/inf-cycle-type? t) :unknown
      (at/refinement-type? t) :unknown
      (at/adapter-leaf-type? t) :unknown
      (at/intersection-type? t) :unknown
      (at/type-var-type? t) :unknown
      (at/forall-type? t) :unknown
      (at/sealed-dyn-type? t) :unknown
      (at/var-type? t) :unknown

      (at/value-type? t)
      (let [v (:value t)]
        (case pred
          :nil? (if (nil? v) :matches :does-not-match)
          :some? (if (nil? v) :does-not-match :matches)
          :string? (if (string? v) :matches :does-not-match)
          :keyword? (if (keyword? v) :matches :does-not-match)
          :integer? (if (integer? v) :matches :does-not-match)
          :number? (if (number? v) :matches :does-not-match)
          :boolean? (if (boolean? v) :matches :does-not-match)
          :symbol? (if (symbol? v) :matches :does-not-match)
          :map? (if (map? v) :matches :does-not-match)
          :vector? (if (vector? v) :matches :does-not-match)
          :set? (if (set? v) :matches :does-not-match)
          :seq? (if (seq? v) :matches :does-not-match)
          :fn? (if (fn? v) :matches :does-not-match)
          :instance?
          (if-let [^Class pc (:class pred-info)]
            (if (instance? pc v) :matches :does-not-match)
            :unknown)
          :unknown))

      (at/ground-type? t)
      (let [g (:ground t)]
        (case pred
          :nil? :does-not-match
          :some? :matches
          :string? (if (= :str g) :matches :does-not-match)
          :keyword? (if (= :keyword g) :matches :does-not-match)
          :integer? (if (= :int g) :matches :does-not-match)
          :number? (if (ground-matches-number? t) :matches :does-not-match)
          :boolean? (if (= :bool g) :matches :does-not-match)
          :symbol? (if (= :symbol g) :matches :does-not-match)
          :map? :does-not-match
          :vector? :does-not-match
          :set? :does-not-match
          :seq? :does-not-match
          :fn? :does-not-match
          :instance?
          (if-let [^Class pc (:class pred-info)]
            (if (instance-ground-assignable? g pc) :matches :does-not-match)
            :unknown)
          :unknown))

      (at/numeric-dyn-type? t)
      (case pred
        :nil? :does-not-match
        :some? :matches
        :number? :matches
        :integer? :unknown
        :string? :does-not-match
        :keyword? :does-not-match
        :boolean? :does-not-match
        :symbol? :does-not-match
        :map? :does-not-match
        :vector? :does-not-match
        :set? :does-not-match
        :seq? :does-not-match
        :fn? :does-not-match
        :instance?
        (numeric-dyn-instance-classification (:class pred-info))
        :unknown)

      (at/map-type? t) (case pred :map? :matches :nil? :does-not-match :some? :matches :does-not-match)
      (at/vector-type? t) (case pred :vector? :matches :nil? :does-not-match :some? :matches :does-not-match)
      (at/set-type? t) (case pred :set? :matches :nil? :does-not-match :some? :matches :does-not-match)
      (at/seq-type? t) (case pred :seq? :matches :nil? :does-not-match :some? :matches :does-not-match)
      (at/fun-type? t) (case pred :fn? :matches :nil? :does-not-match :some? :matches :does-not-match)

      :else :unknown)))

(defn- classify-nil-for-predicate?
  [pred-info]
  (classify-leaf-for-predicate? pred-info (ato/exact-value-type nil)))

(defn- combine-parts
  [parts]
  (let [parts (vec (remove at/bottom-type? parts))]
    (cond
      (empty? parts) at/BottomType
      :else (ato/union-type parts))))

(defn- partition-leaf
  [t pred-info polarity]
  (let [c (classify-leaf-for-predicate? pred-info t)]
    (cond
      (= :unknown c) t
      polarity (case c :matches t :does-not-match at/BottomType :unknown t)
      :else (case c :matches at/BottomType :does-not-match t :unknown t))))

(defn- partition-maybe
  [inner pred-info polarity]
  (let [nil-c (classify-nil-for-predicate? pred-info)
        inner-pos (partition-type-for-predicate* inner pred-info true)
        inner-neg (partition-type-for-predicate* inner pred-info false)]
    (if (= :unknown nil-c)
      (at/->MaybeT (partition-type-for-predicate* inner pred-info polarity))
      (if polarity
        (combine-parts (cond-> []
                        (= :matches nil-c) (conj (ato/exact-value-type nil))
                        (not (at/bottom-type? inner-pos)) (conj inner-pos)))
        (if (= :matches nil-c)
          inner-neg
          (combine-parts (cond-> []
                          (= :does-not-match nil-c) (conj (ato/exact-value-type nil))
                          (not (at/bottom-type? inner-neg)) (conj inner-neg))))))))

(defn- partition-type-for-predicate*
  [type pred-info polarity]
  (let [type (ato/normalize-type type)]
    (cond
      (at/dyn-type? type) type
      (at/placeholder-type? type) type
      (at/inf-cycle-type? type) type

      (at/union-type? type)
      (combine-parts (map #(partition-type-for-predicate* % pred-info polarity) (:members type)))

      (at/maybe-type? type)
      (partition-maybe (:inner type) pred-info polarity)

      (ato/unknown-type? type) type

      :else (partition-leaf type pred-info polarity))))

(defn partition-type-for-predicate
  [type pred-info polarity]
  (partition-type-for-predicate* (ato/normalize-type type) pred-info polarity))

(defn- false-bool-value-type?
  [t]
  (and (at/value-type? t)
       (false? (:value t))))

(defn apply-truthy-local
  [type polarity]
  (if-not polarity
    type
    (let [t (ato/de-maybe-type (ato/normalize-type type))]
      (cond
        (at/union-type? t)
        (let [members (vec (remove false-bool-value-type? (:members t)))]
          (if (empty? members)
            at/BottomType
            (ato/union-type members)))

        (false-bool-value-type? t)
        at/BottomType

        (and (at/value-type? t) (nil? (:value t)))
        at/BottomType

        :else t))))

(defn- value-in-set?
  [v values]
  (some #(= v %) values))

(defn- partition-values-leaf
  [t values polarity]
  (cond
    (at/dyn-type? t) t
    (at/value-type? t)
    (let [v (:value t)
          in? (value-in-set? v values)]
      (if polarity
        (if in? t at/BottomType)
        (if in? at/BottomType t)))

    (at/union-type? t)
    (combine-parts (map #(partition-values-leaf % values polarity) (:members t)))

    :else
    (if polarity at/BottomType t)))

(defn partition-type-for-values
  [type values polarity]
  (let [type (ato/normalize-type type)
        values (vec (distinct values))]
    (cond
      (empty? values) type
      (at/dyn-type? type) type
      (at/placeholder-type? type) type
      (at/inf-cycle-type? type) type
      (ato/unknown-type? type) type
      (at/union-type? type)
      (combine-parts (map #(partition-type-for-values % values polarity) (:members type)))
      (at/maybe-type? type)
      (at/->MaybeT (partition-type-for-values (:inner type) values polarity))
      :else (partition-values-leaf type values polarity))))
