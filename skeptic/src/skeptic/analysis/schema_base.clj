(ns skeptic.analysis.schema-base
  (:require [schema.core :as s])
  (:import [schema.core Both CondPre ConditionalSchema Constrained Either EnumSchema EqSchema FnSchema Maybe NamedSchema Predicate]))

(defn- tagged-map?
  [value tag-key tag]
  (and (map? value)
       (= tag (get value tag-key))))

(defn any-schema?
  [s]
  (= s s/Any))

(defn schema-literal?
  [value]
  (or (keyword? value)
      (string? value)
      (integer? value)
      (boolean? value)
      (symbol? value)))

(defn fn-schema?
  [schema]
  (instance? FnSchema schema))

(defn maybe?
  [s]
  (instance? Maybe s))

(defn named?
  [s]
  (instance? NamedSchema s))

(defn constrained?
  [s]
  (instance? Constrained s))

(defn either?
  [s]
  (instance? Either s))

(defn conditional-schema?
  [s]
  (instance? ConditionalSchema s))

(defn cond-pre?
  [s]
  (instance? CondPre s))

(defn both?
  [s]
  (instance? Both s))

(defn eq?
  [s]
  (instance? EqSchema s))

(defn pred?
  [s]
  (instance? Predicate s))

(defn enum-schema?
  [s]
  (instance? EnumSchema s))

(defn de-maybe
  [s]
  (cond-> s
    (maybe? s)
    :schema))

(defn de-named
  [s]
  (cond-> s
    (named? s)
    :schema))

(defn named-name
  [s]
  (:name s))

(defn de-constrained
  [s]
  (cond-> s
    (constrained? s)
    :schema))

(defn de-eq
  [s]
  (cond-> s
    (eq? s)
    :v))

(defn de-pred
  [s]
  (cond-> s
    (pred? s)
    :pred-name))

(defn de-enum
  [s]
  (cond-> s
    (enum-schema? s)
    :vs))

(def custom-schema-tag-key
  :skeptic.analysis.schema/custom-schema)

(def bottom-schema-tag
  :skeptic.analysis.schema/bottom-schema)

(def join-schema-tag
  :skeptic.analysis.schema/join-schema)

(def valued-schema-tag
  :skeptic.analysis.schema/valued-schema)

(def variable-schema-tag
  :skeptic.analysis.schema/variable-schema)

(defn bottom-schema?
  [s]
  (tagged-map? s custom-schema-tag-key bottom-schema-tag))

(def Bottom
  "Any value, including nil. But often exceptions."
  {custom-schema-tag-key bottom-schema-tag})

(defn join
  [& schemas]
  {custom-schema-tag-key join-schema-tag
   :schemas (into #{} schemas)})

(defn join?
  [s]
  (tagged-map? s custom-schema-tag-key join-schema-tag))

(defn join->set
  [s]
  (if (join? s)
    (:schemas s)
    #{s}))

(defn valued-schema
  [schema value]
  {custom-schema-tag-key valued-schema-tag
   :schema schema
   :value value})

(defn valued-schema?
  [s]
  (tagged-map? s custom-schema-tag-key valued-schema-tag))

(defn variable
  [schema]
  {custom-schema-tag-key variable-schema-tag
   :schema schema})

(defn variable?
  [s]
  (tagged-map? s custom-schema-tag-key variable-schema-tag))

(defn custom-schema?
  [s]
  (or (bottom-schema? s)
      (join? s)
      (valued-schema? s)
      (variable? s)))

(def placeholder-key
  :skeptic.analysis.resolvers/placeholder)

(defn placeholder-schema
  [value]
  {placeholder-key value})

(defn placeholder-schema?
  [schema]
  (and (map? schema)
       (not (record? schema))
       (= 1 (count schema))
       (contains? schema placeholder-key)))

(defn placeholder-ref
  [schema]
  (get schema placeholder-key))

(defn qualified-var-symbol
  [v]
  (let [{:keys [ns name]} (meta v)]
    (when (and ns name)
      (symbol (str (ns-name ns) "/" name)))))

(declare custom-schema-match-value?)

(let [s-checker (memoize (fn [s] (s/checker s)))]
  (defn schema-match-value?
   [s x]
   (try
     (if (custom-schema? s)
       (custom-schema-match-value? s x)
       (nil? ((s-checker s) x)))
     (catch Exception _e
       nil))))

(defn schema-match?
  [s x]
  (or (schema-match-value? s x)
      (try (schema-match-value? s (-> x resolve deref))
           (catch Exception _e
             nil))))

(defn check-if-schema
  [s x]
  (case (schema-match? s x)
    true :skeptic.analysis.schema/plumatic-valid
    false :skeptic.analysis.schema/plumatic-invalid
    nil :skeptic.analysis.schema/value))

(def plumatic-valid
  :skeptic.analysis.schema/plumatic-valid)

(def plumatic-invalid
  :skeptic.analysis.schema/plumatic-invalid)

(def schema-value
  :skeptic.analysis.schema/value)

(defn custom-schema-match-value?
  [s x]
  (cond
    (bottom-schema? s)
    true

    (join? s)
    (let [results (map #(schema-match-value? % x) (:schemas s))]
      (cond
        (some true? results) true
        (some nil? results) nil
        :else false))

    (valued-schema? s)
    (let [schema-result (schema-match-value? (:schema s) x)]
      (cond
        (= x (:value s)) true
        (true? schema-result) true
        (nil? schema-result) nil
        :else false))

    (variable? s)
    (if (var? x)
      (schema-match-value? (:schema s) (deref x))
      false)

    :else nil))

(defn canonical-scalar-schema
  [schema]
  (cond
    (or (= schema :number)
        (= schema :long)
        (= schema :int)
        (= schema :integer))
    s/Int

    (or (= schema s/Int)
        (= schema java.lang.Long)
        (= schema Long/TYPE)
        (= schema java.lang.Integer)
        (= schema Integer/TYPE)
        (= schema java.lang.Short)
        (= schema Short/TYPE)
        (= schema java.lang.Byte)
        (= schema Byte/TYPE)
        (= schema java.math.BigInteger))
    s/Int

    (or (= schema java.lang.Double)
        (= schema Double/TYPE))
    java.lang.Double

    (or (= schema java.lang.Float)
        (= schema Float/TYPE))
    java.lang.Float

    (= schema :string)
    s/Str

    (or (= schema s/Str)
        (= schema java.lang.String))
    s/Str

    (= schema :keyword)
    s/Keyword

    (or (= schema s/Keyword)
        (= schema clojure.lang.Keyword))
    s/Keyword

    (= schema :symbol)
    s/Symbol

    (or (= schema s/Symbol)
        (= schema clojure.lang.Symbol))
    s/Symbol

    (= schema :boolean)
    s/Bool

    (or (= schema s/Bool)
        (= schema java.lang.Boolean)
        (= schema Boolean/TYPE))
    s/Bool

    (= schema :nil)
    nil

    (= schema :object)
    Object

    :else schema))

(defn plain-map-schema?
  [schema]
  (and (map? schema)
       (not (record? schema))
       (not (custom-schema? schema))
       (not (s/optional-key? schema))))

(defn- cartesian
  [coll1 coll2]
  (for [x coll1
        y coll2]
    [x y]))

(defn all-pairs
  [[coll1 & rst]]
  (cond
    (nil? coll1) []
    (empty? rst)  coll1
    :else (mapv flatten (reduce cartesian coll1 (or rst [])))))

(defn flatten-valued-schema-map
  [m]
  (loop [m m]
    (if (and (map? m)
             (valued-schema? m)
             (map? (:schema m))
             (or (some valued-schema? (keys (:schema m)))
                 (some valued-schema? (vals (:schema m)))))
      (recur (:schema m))
      m)))

(defn dynamic-fn-schema
  [arity output]
  (s/make-fn-schema (or output s/Any) [(vec (repeat (or arity 0) (s/one s/Any 'anon-arg)))]))
