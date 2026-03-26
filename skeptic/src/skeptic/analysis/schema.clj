(ns skeptic.analysis.schema
  (:require [schema.core :as s]
            [schema.spec.core :as spec :include-macros true]
            [schema.spec.leaf :as leaf])
  (:import [schema.core EqSchema Maybe One Schema]))

(defn any-schema?
  [s]
  (= s s/Any))

(defn schema?
  [s]
  (or (instance? Schema s)
      (try (s/check s nil)
           true
           (catch Exception _e
             false))))

(defn schema-match-value?
  [s x]
  (try
    (nil? (s/check s x))
    (catch Exception _e
      nil)))

(defn schema-match?
  [s x]
  (or (schema-match-value? s x)
      (try (schema-match-value? s (-> x resolve deref))
           (catch Exception _e
             nil))))

(defn check-if-schema
  [s x]
  (case (schema-match? s x)
    true ::schema-valid
    false ::schema-invalid
    nil ::value))

(defn fn-schema?
  [schema]
  (try
    (boolean (:input-schemas (into {} schema)))
    (catch Exception _e
      false)))

(defn maybe?
  [s]
  (instance? Maybe s))

(defn eq?
  [s]
  (instance? EqSchema s))

(defn de-maybe
  [s]
  (cond-> s
    (maybe? s)
    :schema))

(defn de-eq
  [s]
  (cond-> s
    (eq? s)
    :v))

(declare canonicalize-schema
         canonicalize-entry-fn-schema
         variable
         matches-map)

(defrecord BottomSchema [_]
  ;; This is copied from Any, but in terms of calculating joins of types it works in practically the opposite fashion
  ;; i.e. Any && x = x, Any || x = Any
  ;;      Bottom && x = Bottom, Bottom || x = x
  Schema
  (spec [this] (leaf/leaf-spec spec/+no-precondition+))
  (explain [this] 'Bottom))

(def Bottom
  "Any value, including nil. But often exceptions."
  (BottomSchema. nil))

(defrecord Join [schemas]
  ;; This is basically either, except that it isn't deprecated and doesn't care about order
  ;; It is intended for use in analysis rather than directly in programs, to represent an unresolved Join of the included
  ;; schemas (which often will be simply x || y || z || ... for distinct schemas x, y, z, ..., but may be able to be restricted
  ;; in the case of maps with overlapping keys, numeric types, etc.)
  Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this set? #(list 'set? schemas %))))
  (explain [_this] (into #{} (map s/explain schemas))))

(defn join
  [& schemas]
  (Join. (into #{} schemas)))

(defn join?
  [s]
  (instance? Join s))

(defn join->set
  [s]
  (if (join? s)
    (:schemas s)
    #{s}))

(defn schema-join
  ;; Nils treated as an automatic `maybe`; this isn't strictly necessary, as `maybe x` is just `nil || x`, but `nil` analysis is
  ;; important enough that they are treated as a separate case
  [[t1 & _r :as types]]
  (let [types (cond->> types (not (set? types)) (into #{}))]
    (cond
      (= 1 (count types)) t1

      (contains? types nil)
      (s/maybe (schema-join (disj types nil)))

      (empty? types)
      s/Any

      :else
      (apply join types))))

(defrecord ValuedSchema [schema value]
  Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this #(instance? Schema (:schema %)) (fn [s] s))))
  (explain [_this] (str value " : " (s/explain schema))))

(defn valued-schema
  [schema value]
  (ValuedSchema. schema value))

(defn valued-schema?
  [s]
  (instance? ValuedSchema s))

(defrecord Variable [schema]
  Schema
  (spec [this] (leaf/leaf-spec (spec/precondition this #(and (var? %) (nil? (s/check schema (deref %)))) #(list 'var? schema %))))
  (explain [_this] (list "#'" (s/explain schema))))

(defn variable
  [schema]
  (Variable. schema))

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

(defn canonical-scalar-schema
  [schema]
  (cond
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

    (or (= schema s/Str)
        (= schema java.lang.String))
    s/Str

    (or (= schema s/Keyword)
        (= schema clojure.lang.Keyword))
    s/Keyword

    (or (= schema s/Symbol)
        (= schema clojure.lang.Symbol))
    s/Symbol

    (or (= schema s/Bool)
        (= schema java.lang.Boolean)
        (= schema Boolean/TYPE))
    s/Bool

    :else schema))

(defn canonicalize-one
  [one]
  (let [m (try (into {} one)
               (catch Exception _e nil))]
    (if (map? m)
      (s/one (canonicalize-schema (:schema m))
             (:name m))
      one)))

(defn canonicalize-map-key
  [k]
  (if (s/optional-key? k)
    (s/optional-key (canonicalize-schema (:k k)))
    (canonicalize-schema k)))

(defn canonicalize-entry
  [entry]
  (cond
    (nil? entry) nil
    (not (map? entry)) entry
    :else
    (cond-> entry
      (contains? entry :schema) (update :schema canonicalize-schema)
      (contains? entry :output) (update :output canonicalize-schema)
      (contains? entry :expected-arglist) (update :expected-arglist #(mapv canonicalize-schema %))
      (contains? entry :actual-arglist) (update :actual-arglist #(mapv canonicalize-schema %))
      (contains? entry :locals) (update :locals (fn [locals]
                                                  (into {}
                                                        (map (fn [[k v]]
                                                               [k (canonicalize-entry v)]))
                                                        locals)))
      (contains? entry :arglists) (update :arglists (fn [arglists]
                                                      (into {}
                                                            (map (fn [[k v]]
                                                                   [k (canonicalize-entry v)]))
                                                            arglists)))
      (contains? entry :schema) canonicalize-entry-fn-schema)))

(defn canonicalize-fn-schema
  [schema]
  (let [{:keys [input-schemas output-schema]} (into {} schema)]
    (s/make-fn-schema (canonicalize-schema output-schema)
                      (mapv (fn [inputs]
                              (mapv canonicalize-one inputs))
                            input-schemas))))

(defn canonicalize-entry-fn-schema
  [entry]
  (if (and (contains? entry :schema)
           (fn-schema? (:schema entry)))
    (assoc entry :schema (canonicalize-fn-schema (:schema entry)))
    entry))

(defn canonicalize-schema
  [schema]
  (cond
    (nil? schema) nil
    (placeholder-schema? schema) schema
    (fn-schema? schema) (canonicalize-fn-schema schema)
    (instance? One schema) (canonicalize-one schema)
    (maybe? schema) (s/maybe (canonicalize-schema (:schema schema)))
    (join? schema) (schema-join (set (map canonicalize-schema (:schemas schema))))
    (valued-schema? schema) (valued-schema (canonicalize-schema (:schema schema))
                                           (:value schema))
    (instance? Variable schema) (variable (canonicalize-schema (:schema schema)))
    (contains? #{s/Int s/Str s/Keyword s/Symbol s/Bool}
               (canonical-scalar-schema schema))
    (canonical-scalar-schema schema)
    (record? schema) schema
    (map? schema) (into {}
                       (map (fn [[k v]]
                              [(canonicalize-map-key k)
                               (canonicalize-schema v)]))
                       schema)
    (vector? schema) (mapv canonicalize-schema schema)
    (set? schema) (into #{} (map canonicalize-schema) schema)
    (seq? schema) (doall (map canonicalize-schema schema))
    :else (canonical-scalar-schema schema)))

(defn plain-map-schema?
  [schema]
  (and (map? schema)
       (not (record? schema))
       (not (s/optional-key? schema))))

(defn maybe-schema
  [schema]
  (let [schema (canonicalize-schema schema)]
    (if (maybe? schema)
      schema
      (s/maybe schema))))

(declare semantic-value-schema)

(defn semantic-value-schema
  [schema]
  (let [schema (canonicalize-schema schema)]
    (cond
      (placeholder-schema? schema) schema
      (valued-schema? schema) (semantic-value-schema (:schema schema))
      (maybe? schema) (s/maybe (semantic-value-schema (:schema schema)))
      (join? schema) (schema-join (set (map semantic-value-schema (:schemas schema))))
      (instance? Variable schema) (variable (semantic-value-schema (:schema schema)))
      (plain-map-schema? schema) (into {}
                                     (map (fn [[k v]]
                                            [(semantic-value-schema k)
                                             (semantic-value-schema v)]))
                                     schema)
      (vector? schema) (mapv semantic-value-schema schema)
      (set? schema) (into #{} (map semantic-value-schema) schema)
      (seq? schema) (doall (map semantic-value-schema schema))
      :else schema)))

(declare schema-compatible?
         schema-equivalent?
         valued-compatible?)

(defn nested-value-compatible?
  [expected actual]
  (let [actual (canonicalize-schema actual)]
    (if (valued-schema? actual)
      (or (schema-compatible? expected (:value actual))
          (schema-compatible? expected (:schema actual)))
      (schema-compatible? expected actual))))

(defn exact-key-candidate-groups
  [k]
  (let [k (canonicalize-schema k)]
    (->> (cond
           (valued-schema? k)
           [[(:value k)
             (when (keyword? (:value k))
               (s/optional-key (:value k)))]
            [(:schema k)
             (when (or (keyword? (:schema k))
                       (schema? (:schema k)))
               (s/optional-key (:schema k)))]]

           :else
           [[k
             (when (keyword? k)
               (s/optional-key k))]])
         (mapv (fn [group]
                 (vec (distinct (remove nil? group)))))
         (remove empty?))))

(defn matching-map-entry
  [m k]
  (let [m (canonicalize-schema m)
        k (canonicalize-schema k)
        exact-matches (some (fn [group]
                              (let [group-set (set group)
                                    matches (->> m
                                                 keys
                                                 (filter #(contains? group-set %))
                                                 seq)]
                                (when matches matches)))
                            (exact-key-candidate-groups k))
        matches (if (seq exact-matches)
                  exact-matches
                  (->> m
                       keys
                       (filter #(valued-compatible? % k))))]
    (cond
      (empty? matches) nil
      (> (count matches) 1) (throw (IllegalStateException.
                                    (format "Multiple results for key %s and m %s: %s"
                                            k m matches)))
      :else (let [matched-key (first matches)]
              [matched-key (get m matched-key)]))))

(def no-default ::no-default)

(defn map-get-schema
  ([m key]
   (map-get-schema m key no-default))
  ([m key default]
   (let [m (canonicalize-schema m)
         key (canonicalize-schema key)
         default-provided? (not= default no-default)
         default-schema (when default-provided?
                          (canonicalize-schema default))]
     (cond
       (maybe? m)
       (schema-join
        [(map-get-schema (de-maybe m) key default)
         (or default-schema (s/maybe s/Any))])

       (join? m)
       (schema-join (set (map #(map-get-schema % key default) (:schemas m))))

       (plain-map-schema? m)
       (if-let [[matched-key matched-value] (matching-map-entry m key)]
         (let [base-value (semantic-value-schema matched-value)
               base-value (if (and (s/optional-key? matched-key)
                                   (not default-provided?))
                            (maybe-schema base-value)
                            base-value)]
           (if default-provided?
             (schema-join [base-value default-schema])
             base-value))
         (if default-provided?
           default-schema
           s/Any))

       :else
       (if default-provided?
         (schema-join [s/Any default-schema])
         s/Any)))))

(defn merge-map-schemas
  [schemas]
  (let [schemas (mapv canonicalize-schema schemas)]
    (if (every? plain-map-schema? schemas)
      (reduce merge {} schemas)
      s/Any)))

(defn schema-equivalent?
  [expected actual]
  (= (canonicalize-schema expected)
     (canonicalize-schema actual)))

(defn unknown-schema?
  [schema]
  (let [schema (canonicalize-schema schema)]
    (cond
      (placeholder-schema? schema) true
      (any-schema? schema) true
      (or (= schema s/Num)
          (= schema Number)
          (= schema java.lang.Number)
          (= schema Object)
          (= schema java.lang.Object)) true
      (maybe? schema) (unknown-schema? (de-maybe schema))
      (join? schema) (some unknown-schema? (:schemas schema))
      :else false)))

(declare resolve-placeholders)

(defn resolve-placeholders
  [schema resolve-placeholder]
  (let [schema (canonicalize-schema schema)]
    (cond
      (placeholder-schema? schema)
      (canonicalize-schema (or (resolve-placeholder (placeholder-ref schema))
                               s/Any))

      (fn-schema? schema)
      (let [{:keys [input-schemas output-schema]} (into {} schema)]
        (s/make-fn-schema (resolve-placeholders output-schema resolve-placeholder)
                          (mapv (fn [inputs]
                                  (mapv (fn [one]
                                          (let [m (try (into {} one)
                                                       (catch Exception _e nil))]
                                            (if (map? m)
                                              (s/one (resolve-placeholders (:schema m) resolve-placeholder)
                                                     (:name m))
                                              one)))
                                        inputs))
                                input-schemas)))

      (instance? One schema)
      (canonicalize-one (assoc (into {} schema)
                               :schema (resolve-placeholders (:schema schema)
                                                            resolve-placeholder)))

      (maybe? schema)
      (s/maybe (resolve-placeholders (:schema schema) resolve-placeholder))

      (join? schema)
      (schema-join (set (map #(resolve-placeholders % resolve-placeholder)
                             (:schemas schema))))

      (valued-schema? schema)
      (valued-schema (resolve-placeholders (:schema schema) resolve-placeholder)
                     (:value schema))

      (instance? Variable schema)
      (variable (resolve-placeholders (:schema schema) resolve-placeholder))

      (record? schema)
      schema

      (map? schema)
      (into {}
            (map (fn [[k v]]
                   [(resolve-placeholders k resolve-placeholder)
                    (resolve-placeholders v resolve-placeholder)]))
            schema)

      (vector? schema)
      (mapv #(resolve-placeholders % resolve-placeholder) schema)

      (set? schema)
      (into #{} (map #(resolve-placeholders % resolve-placeholder)) schema)

      (seq? schema)
      (doall (map #(resolve-placeholders % resolve-placeholder) schema))

      :else schema)))

(defn valued-compatible?
  [expected actual]
  (let [expected (canonicalize-schema expected)
        actual (canonicalize-schema actual)]
    (cond
      (valued-schema? expected)
      (throw (IllegalArgumentException. "Only actual can be a valued schema"))

      (valued-schema? actual)
      (let [v (:value actual)
            s (:schema actual)
            e (de-maybe expected)]
        (or (schema-equivalent? e v)
            (schema-equivalent? e s)
            (schema-equivalent? e (s/optional-key v))
            (schema-equivalent? e (s/optional-key s))
            (= (check-if-schema e v) ::schema-valid)))

      (or (schema-equivalent? expected actual)
          (schema-equivalent? expected (s/optional-key actual))
          (= (check-if-schema expected actual) ::schema-valid))
      true

      (and (map? expected) (map? actual))
      (every? (fn [[k v]] (matches-map expected k v)) actual)

      :else false)))

(defn get-by-matching-schema
  [m k]
  (let [m (canonicalize-schema m)
        k (canonicalize-schema k)
        exact-matches (some (fn [group]
                              (let [group-set (set group)
                                    matches (select-keys m (filter #(contains? group-set %) (keys m)))]
                                (when (seq matches) matches)))
                            (exact-key-candidate-groups k))
        matches (if (seq exact-matches)
                  exact-matches
                  (->> m
                       keys
                       (filter (fn [schema]
                                 (or (schema-equivalent? schema k)
                                     (= (check-if-schema schema k) ::schema-valid))))
                       (select-keys m)))]
    (cond
      (empty? matches) nil
      (> (count matches) 1) (throw (IllegalStateException. (format "Multiple results for key %s and m %s: %s"
                                                                   k m matches)))
      :else (-> matches vals first))))

(defn valued-get
  [m k]
  (let [m (canonicalize-schema m)
        k (canonicalize-schema k)]
    (cond
      (valued-schema? k)
      (or (get m (:value k))
          (get-by-matching-schema m (:value k))
          (get m (:schema k)))

      :else
      (or (get m k)
          (get-by-matching-schema m k)))))

(declare matches-map)

(defn matches-map
  [expected actual-k actual-v]
  (let [expected (canonicalize-schema expected)
        actual-k (canonicalize-schema actual-k)
        actual-v (canonicalize-schema actual-v)
        possible-keys (filter (fn [x] (valued-compatible? x actual-k)) (keys expected))
        expected-vs (map #(valued-get expected %) possible-keys)]
    (if (empty? expected-vs)
      false
      (seq (filter #(nested-value-compatible? % actual-v) expected-vs)))))

(defn required-key?
  [k]
  (and (not (s/optional-key? k))
       (or (keyword? k)
           (valued-schema? k)
           (schema? k)
           (map? k))))

(defn map-schema-compatible?
  [expected actual]
  (let [expected (canonicalize-schema expected)
        actual (canonicalize-schema actual)
        expected-keys (keys expected)
        actual-keys (keys actual)
        required-keys (filter required-key? expected-keys)]
    (and
     (every? (fn [[actual-k actual-v]]
               (matches-map expected actual-k actual-v))
             actual)
     (every? (fn [expected-k]
               (some #(valued-compatible? (if (s/optional-key? expected-k) (:k expected-k) expected-k)
                                          (if (s/optional-key? %) (:k %) %))
                     actual-keys))
             required-keys)
     (every? (fn [actual-k]
               (some #(valued-compatible? %
                                          (if (s/optional-key? actual-k) (:k actual-k) actual-k))
                     expected-keys))
             actual-keys))))

(defn collection-schema-compatible?
  [expected actual]
  (and (= (type expected) (type actual))
       (= (count expected) (count actual))
       (every? true? (map schema-compatible? expected actual))))

(defn schema-compatible?
  [expected actual]
  (let [expected (canonicalize-schema expected)
        actual (canonicalize-schema actual)]
    (cond
      (= actual Bottom) true
      (any-schema? expected) true
      (unknown-schema? actual) true
      (schema-equivalent? expected actual) true
      (and (join? expected) (join? actual))
      (every? (fn [schema]
                (some #(schema-compatible? % schema) (:schemas expected)))
              (:schemas actual))
      (join? expected)
      (some #(schema-compatible? % actual) (:schemas expected))
      (join? actual)
      (every? #(schema-compatible? expected %) (:schemas actual))
      (and (maybe? expected) (maybe? actual))
      (schema-compatible? (de-maybe expected) (de-maybe actual))
      (maybe? expected)
      (schema-compatible? (de-maybe expected) actual)
      (maybe? actual) false
      (and (map? expected) (map? actual))
      (map-schema-compatible? expected actual)
      (and (vector? expected) (vector? actual))
      (collection-schema-compatible? expected actual)
      (and (set? expected) (set? actual))
      (collection-schema-compatible? (vec expected) (vec actual))
      (and (seq? expected) (seq? actual))
      (collection-schema-compatible? (vec expected) (vec actual))
      :else
      (= (check-if-schema expected actual) ::schema-valid))))

(defn cartesian
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

;; TODO: This should either be pushed into the constructor or handled in original analysis
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

(defn schema-values
  [s]
  (cond
    (valued-schema? s) [(:schema s) (:value s)]
    (and (map? s)
         (not (s/optional-key? s)))
    (let [{valued-schemas true base-schemas false} (->> s keys (group-by valued-schema?))

          complex-keys (->> valued-schemas
                            (map (fn [k] (let [v (get s k)]
                                      (map (fn [k2]
                                             {k2 (if (and (schema? k2) (valued-schema? v))
                                                   (:schema v)
                                                   v)})
                                           (schema-values k)))))
                            all-pairs
                            (map (partial into {})))

          complex-values (->> base-schemas
                              (map (fn [k]
                                     (let [v (get s k)]
                                       (map (fn [v2] {k v2})
                                            (if (valued-schema? v) (schema-values v) [v])))))
                              all-pairs
                              (map (partial into {})))

          split-keys (mapcat (fn [vs] (mapv #(merge vs %) complex-keys)) complex-values)]
      split-keys)
    :else [s]))

(defn dynamic-fn-schema
  [arity output]
  (s/make-fn-schema (or output s/Any) [(vec (repeat (or arity 0) (s/one s/Any 'anon-arg)))]))

(s/defschema WithPlaceholder
  {s/Keyword s/Any})

(s/defschema ArgCount
  (s/cond-pre s/Int (s/eq :varargs)))

;; TODO: make these all ns-specific so there are no collisions

(s/defschema AnnotatedExpression
  {:expr s/Any
   :idx s/Int

   (s/optional-key :resolution-path) [s/Any]
   (s/optional-key :schema) s/Any
   (s/optional-key :name) s/Symbol
   (s/optional-key :path) [s/Symbol]
   (s/optional-key :fn-position?) s/Bool
   (s/optional-key :local-vars) {s/Symbol s/Any}
   (s/optional-key :args) [s/Int]
   (s/optional-key :dep-callback) (s/=> (s/recursive #'AnnotatedExpression)
                                        {s/Int (s/recursive #'AnnotatedExpression)} (s/recursive #'AnnotatedExpression))
   (s/optional-key :expected-arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :actual-arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :output) s/Any
   (s/optional-key :arglists) {ArgCount s/Any}
   (s/optional-key :arglist) (s/cond-pre WithPlaceholder [s/Any])
   (s/optional-key :map?) s/Bool
   (s/optional-key :finished?) s/Bool})
