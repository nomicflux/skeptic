(ns skeptic.analysis.schema
  (:require [schema.core :as s])
  (:import [schema.core Both CondPre ConditionalSchema Constrained Either EqSchema FnSchema Maybe NamedSchema One Schema]))

(def custom-schema-tag-key
  ::custom-schema)

(def semantic-type-tag-key
  ::semantic-type)

(defn tagged-map?
  [value tag-key tag]
  (and (map? value)
       (= tag (get value tag-key))))

(defn same-class-name?
  [value class-name]
  (and (some? value)
       (= class-name (.getName (class value)))))

(defn read-instance-field
  [value field-name]
  (let [field (.getDeclaredField (class value) field-name)]
    (.setAccessible field true)
    (.get field value)))

(declare custom-schema?
         custom-schema-match-value?
         schema-explain
         canonicalize-schema
         canonicalize-schema*
         canonicalize-output-schema
         canonicalize-entry-fn-schema
         union-like-branches
         both-components
         localize-schema-value
         variable
         variable?
         dyn-type?
         bottom-type?
         scalar-type?
         fn-method-type?
         fun-type?
         maybe-type?
         union-type?
         intersection-type?
         map-type?
         vector-type?
         set-type?
         seq-type?
         var-type?
         placeholder-type?
         value-type?
         semantic-type-value?
         matches-map
         plain-map-schema?)

(defn any-schema?
  [s]
  (= s s/Any))

(defn schema?
  [s]
  (or (custom-schema? s)
      (instance? Schema s)
      (try (s/check s nil)
           true
           (catch Exception _e
             false))))

(defn schema-match-value?
  [s x]
  (try
    (if (custom-schema? s)
      (custom-schema-match-value? s x)
      (nil? (s/check s x)))
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

(def bottom-schema-tag
  ::bottom-schema)

(def join-schema-tag
  ::join-schema)

(def valued-schema-tag
  ::valued-schema)

(def variable-schema-tag
  ::variable-schema)

(defn bottom-schema?
  [s]
  (or (tagged-map? s custom-schema-tag-key bottom-schema-tag)
      (same-class-name? s "skeptic.analysis.schema.BottomSchema")))

(def Bottom
  "Any value, including nil. But often exceptions."
  {custom-schema-tag-key bottom-schema-tag})

(defn join
  [& schemas]
  {custom-schema-tag-key join-schema-tag
   :schemas (into #{} schemas)})

(defn join?
  [s]
  (or (tagged-map? s custom-schema-tag-key join-schema-tag)
      (same-class-name? s "skeptic.analysis.schema.Join")))

(defn join->set
  [s]
  (if (join? s)
    (:schemas s)
    #{s}))

(defn flatten-join-members
  [types]
  (->> types
       (map canonicalize-schema)
       (mapcat (fn [schema]
                 (if (join? schema)
                   (:schemas schema)
                   [schema])))
       set))

(defn nil-bearing-join
  [types]
  (let [types (flatten-join-members types)
        nil-bearing? (or (contains? types nil)
                         (some maybe? types))
        types (disj types nil)
        {maybe-types true
         plain-types false} (group-by maybe? types)
        maybe-bases (->> maybe-types
                         (map (comp canonicalize-schema de-maybe))
                         set)
        maybe-bases (if (and (contains? maybe-bases s/Any)
                             (seq (concat plain-types
                                          (disj maybe-bases s/Any))))
                      (disj maybe-bases s/Any)
                      maybe-bases)]
    {:nil-bearing? nil-bearing?
     :types (into (set plain-types) maybe-bases)}))

(defn schema-join
  ;; Nils treated as an automatic `maybe`; this isn't strictly necessary, as `maybe x` is just `nil || x`, but `nil` analysis is
  ;; important enough that they are treated as a separate case
  [[t1 & _r :as types]]
  (let [{:keys [nil-bearing? types]}
        (nil-bearing-join (cond->> types (not (set? types)) (into #{})))]
    (cond
      (empty? types)
      (if nil-bearing?
        (s/maybe s/Any)
        s/Any)

      (= 1 (count types))
      (let [schema (first types)]
        (if nil-bearing?
          (s/maybe schema)
          schema))

      :else
      (let [schema (apply join types)]
        (if nil-bearing?
          (s/maybe schema)
          schema)))))

(defn valued-schema
  [schema value]
  {custom-schema-tag-key valued-schema-tag
   :schema schema
   :value value})

(defn valued-schema?
  [s]
  (or (tagged-map? s custom-schema-tag-key valued-schema-tag)
      (same-class-name? s "skeptic.analysis.schema.ValuedSchema")))

(defn variable
  [schema]
  {custom-schema-tag-key variable-schema-tag
   :schema schema})

(defn variable?
  [s]
  (or (tagged-map? s custom-schema-tag-key variable-schema-tag)
      (same-class-name? s "skeptic.analysis.schema.Variable")))

(defn custom-schema?
  [s]
  (or (bottom-schema? s)
      (join? s)
      (valued-schema? s)
      (variable? s)))

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

(defn schema-explain
  [schema]
  (cond
    (bottom-schema? schema)
    'Bottom

    (join? schema)
    (into #{} (map (fn [member]
                     (if (or (schema? member)
                             (class? member))
                       (schema-explain member)
                       member))
                   (:schemas schema)))

    (valued-schema? schema)
    (str (:value schema) " : " (schema-explain (:schema schema)))

    (variable? schema)
    (list "#'" (schema-explain (:schema schema)))

    (or (schema? schema)
        (class? schema))
    (s/explain schema)

    :else schema))

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

(defn canonicalize-schema*
  [schema {:keys [constrained->base?]}]
  (let [schema (localize-schema-value schema)]
    (cond
    (nil? schema) nil
    (named? schema) (canonicalize-schema* (de-named schema)
                                          {:constrained->base? constrained->base?})
    (placeholder-schema? schema) schema
    (bottom-schema? schema) Bottom
    (semantic-type-value? schema) schema
    (fn-schema? schema) (canonicalize-fn-schema schema)
    (instance? One schema) (canonicalize-one schema)
    (maybe? schema) (s/maybe (canonicalize-schema* (:schema schema)
                                                   {:constrained->base? constrained->base?}))
    (constrained? schema) (if constrained->base?
                           (canonicalize-schema* (de-constrained schema)
                                                 {:constrained->base? true})
                           (s/constrained (canonicalize-schema* (de-constrained schema)
                                                                {:constrained->base? false})
                                          (:postcondition schema)
                                          (:post-name schema)))
    (either? schema) (apply s/either
                            (map #(canonicalize-schema* %
                                                        {:constrained->base? constrained->base?})
                                 (:schemas schema)))
    (conditional-schema? schema) (let [branches (mapcat (fn [[pred branch]]
                                                          [pred (canonicalize-schema* branch
                                                                                     {:constrained->base? constrained->base?})])
                                                        (:preds-and-schemas schema))
                                       args (cond-> (vec branches)
                                              (:error-symbol schema) (conj (:error-symbol schema)))]
                                   (apply s/conditional args))
    (cond-pre? schema) (apply s/cond-pre
                              (map #(canonicalize-schema* %
                                                          {:constrained->base? constrained->base?})
                                   (:schemas schema)))
    (both? schema) (apply s/both
                          (map #(canonicalize-schema* %
                                                      {:constrained->base? constrained->base?})
                               (:schemas schema)))
    (join? schema) (schema-join (set (map #(canonicalize-schema* %
                                                                {:constrained->base? constrained->base?})
                                          (:schemas schema))))
    (valued-schema? schema) (valued-schema (canonicalize-schema* (:schema schema)
                                                                 {:constrained->base? constrained->base?})
                                           (:value schema))
    (variable? schema) (variable (canonicalize-schema* (:schema schema)
                                                       {:constrained->base? constrained->base?}))
    (contains? #{s/Int s/Str s/Keyword s/Symbol s/Bool}
               (canonical-scalar-schema schema))
    (canonical-scalar-schema schema)
    (record? schema) schema
    (map? schema) (into {}
                       (map (fn [[k v]]
                              [(if (s/optional-key? k)
                                 (s/optional-key (canonicalize-schema* (:k k)
                                                                       {:constrained->base? constrained->base?}))
                                 (canonicalize-schema* k {:constrained->base? constrained->base?}))
                               (canonicalize-schema* v {:constrained->base? constrained->base?})]))
                       schema)
    (vector? schema) (mapv #(canonicalize-schema* % {:constrained->base? constrained->base?}) schema)
    (set? schema) (into #{} (map #(canonicalize-schema* % {:constrained->base? constrained->base?})) schema)
    (seq? schema) (doall (map #(canonicalize-schema* % {:constrained->base? constrained->base?}) schema))
    :else (canonical-scalar-schema schema))))

(defn canonicalize-schema
  [schema]
  (canonicalize-schema* schema {:constrained->base? false}))

(defn canonicalize-output-schema
  [schema]
  (canonicalize-schema* schema {:constrained->base? true}))

(def dyn-type-tag
  ::dyn-type)

(def bottom-type-tag
  ::bottom-type)

(def scalar-type-tag
  ::scalar-type)

(def fn-method-type-tag
  ::fn-method-type)

(def fun-type-tag
  ::fun-type)

(def maybe-type-tag
  ::maybe-type)

(def union-type-tag
  ::union-type)

(def intersection-type-tag
  ::intersection-type)

(def map-type-tag
  ::map-type)

(def vector-type-tag
  ::vector-type)

(def set-type-tag
  ::set-type)

(def seq-type-tag
  ::seq-type)

(def var-type-tag
  ::var-type)

(def placeholder-type-tag
  ::placeholder-type)

(def value-type-tag
  ::value-type)

(defn ->DynT
  []
  {semantic-type-tag-key dyn-type-tag})

(defn ->BottomT
  []
  {semantic-type-tag-key bottom-type-tag})

(defn ->ScalarT
  [schema]
  {semantic-type-tag-key scalar-type-tag
   :schema schema})

(defn ->FnMethodT
  [inputs output min-arity variadic?]
  {semantic-type-tag-key fn-method-type-tag
   :inputs inputs
   :output output
   :min-arity min-arity
   :variadic? variadic?})

(defn ->FunT
  [methods]
  {semantic-type-tag-key fun-type-tag
   :methods methods})

(defn ->MaybeT
  [inner]
  {semantic-type-tag-key maybe-type-tag
   :inner inner})

(defn ->UnionT
  [members]
  {semantic-type-tag-key union-type-tag
   :members members})

(defn ->IntersectionT
  [members]
  {semantic-type-tag-key intersection-type-tag
   :members members})

(defn ->MapT
  [entries]
  {semantic-type-tag-key map-type-tag
   :entries entries})

(defn ->VectorT
  [items homogeneous?]
  {semantic-type-tag-key vector-type-tag
   :items items
   :homogeneous? homogeneous?})

(defn ->SetT
  [members homogeneous?]
  {semantic-type-tag-key set-type-tag
   :members members
   :homogeneous? homogeneous?})

(defn ->SeqT
  [items homogeneous?]
  {semantic-type-tag-key seq-type-tag
   :items items
   :homogeneous? homogeneous?})

(defn ->VarT
  [inner]
  {semantic-type-tag-key var-type-tag
   :inner inner})

(defn ->PlaceholderT
  [ref]
  {semantic-type-tag-key placeholder-type-tag
   :ref ref})

(defn ->ValueT
  [inner value]
  {semantic-type-tag-key value-type-tag
   :inner inner
   :value value})

(def Dyn
  (->DynT))

(def BottomType
  (->BottomT))

(defn localize-schema-value
  [value]
  (cond
    (nil? value) nil
    (bottom-schema? value) Bottom
    (join? value)
    (apply join (localize-schema-value (:schemas value)))
    (same-class-name? value "skeptic.analysis.schema.Join")
    (apply join (localize-schema-value (read-instance-field value "schemas")))
    (valued-schema? value)
    (valued-schema (localize-schema-value (:schema value))
                   (localize-schema-value (:value value)))
    (same-class-name? value "skeptic.analysis.schema.ValuedSchema")
    (valued-schema (localize-schema-value (read-instance-field value "schema"))
                   (localize-schema-value (read-instance-field value "value")))
    (variable? value)
    (variable (localize-schema-value (:schema value)))
    (same-class-name? value "skeptic.analysis.schema.Variable")
    (variable (localize-schema-value (read-instance-field value "schema")))
    (dyn-type? value) Dyn
    (bottom-type? value) BottomType
    (scalar-type? value)
    (->ScalarT (localize-schema-value (:schema value)))
    (same-class-name? value "skeptic.analysis.schema.ScalarT")
    (->ScalarT (localize-schema-value (read-instance-field value "schema")))
    (fn-method-type? value)
    (->FnMethodT (localize-schema-value (:inputs value))
                 (localize-schema-value (:output value))
                 (:min-arity value)
                 (:variadic? value))
    (same-class-name? value "skeptic.analysis.schema.FnMethodT")
    (->FnMethodT (localize-schema-value (read-instance-field value "inputs"))
                 (localize-schema-value (read-instance-field value "output"))
                 (read-instance-field value "min_arity")
                 (read-instance-field value "variadic_QMARK_"))
    (fun-type? value)
    (->FunT (localize-schema-value (:methods value)))
    (same-class-name? value "skeptic.analysis.schema.FunT")
    (->FunT (localize-schema-value (read-instance-field value "methods")))
    (maybe-type? value)
    (->MaybeT (localize-schema-value (:inner value)))
    (same-class-name? value "skeptic.analysis.schema.MaybeT")
    (->MaybeT (localize-schema-value (read-instance-field value "inner")))
    (union-type? value)
    (->UnionT (localize-schema-value (:members value)))
    (same-class-name? value "skeptic.analysis.schema.UnionT")
    (->UnionT (localize-schema-value (read-instance-field value "members")))
    (intersection-type? value)
    (->IntersectionT (localize-schema-value (:members value)))
    (same-class-name? value "skeptic.analysis.schema.IntersectionT")
    (->IntersectionT (localize-schema-value (read-instance-field value "members")))
    (map-type? value)
    (->MapT (localize-schema-value (:entries value)))
    (same-class-name? value "skeptic.analysis.schema.MapT")
    (->MapT (localize-schema-value (read-instance-field value "entries")))
    (vector-type? value)
    (->VectorT (localize-schema-value (:items value))
               (:homogeneous? value))
    (same-class-name? value "skeptic.analysis.schema.VectorT")
    (->VectorT (localize-schema-value (read-instance-field value "items"))
               (read-instance-field value "homogeneous_QMARK_"))
    (set-type? value)
    (->SetT (localize-schema-value (:members value))
            (:homogeneous? value))
    (same-class-name? value "skeptic.analysis.schema.SetT")
    (->SetT (localize-schema-value (read-instance-field value "members"))
            (read-instance-field value "homogeneous_QMARK_"))
    (seq-type? value)
    (->SeqT (localize-schema-value (:items value))
            (:homogeneous? value))
    (same-class-name? value "skeptic.analysis.schema.SeqT")
    (->SeqT (localize-schema-value (read-instance-field value "items"))
            (read-instance-field value "homogeneous_QMARK_"))
    (var-type? value)
    (->VarT (localize-schema-value (:inner value)))
    (same-class-name? value "skeptic.analysis.schema.VarT")
    (->VarT (localize-schema-value (read-instance-field value "inner")))
    (placeholder-type? value)
    (->PlaceholderT (localize-schema-value (:ref value)))
    (same-class-name? value "skeptic.analysis.schema.PlaceholderT")
    (->PlaceholderT (localize-schema-value (read-instance-field value "ref")))
    (value-type? value)
    (->ValueT (localize-schema-value (:inner value))
              (localize-schema-value (:value value)))
    (same-class-name? value "skeptic.analysis.schema.ValueT")
    (->ValueT (localize-schema-value (read-instance-field value "inner"))
              (localize-schema-value (read-instance-field value "value")))
    (vector? value) (mapv localize-schema-value value)
    (set? value) (into #{} (map localize-schema-value) value)
    (and (map? value) (not (record? value)))
    (into {}
          (map (fn [[k v]]
                 [(localize-schema-value k)
                  (localize-schema-value v)]))
          value)
    (seq? value) (doall (map localize-schema-value value))
    :else value))

(defn dyn-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key dyn-type-tag)
      (same-class-name? t "skeptic.analysis.schema.DynT")))

(defn bottom-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key bottom-type-tag)
      (same-class-name? t "skeptic.analysis.schema.BottomT")))

(defn scalar-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key scalar-type-tag)
      (same-class-name? t "skeptic.analysis.schema.ScalarT")))

(defn fn-method-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key fn-method-type-tag)
      (same-class-name? t "skeptic.analysis.schema.FnMethodT")))

(defn fun-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key fun-type-tag)
      (same-class-name? t "skeptic.analysis.schema.FunT")))

(defn maybe-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key maybe-type-tag)
      (same-class-name? t "skeptic.analysis.schema.MaybeT")))

(defn union-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key union-type-tag)
      (same-class-name? t "skeptic.analysis.schema.UnionT")))

(defn intersection-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key intersection-type-tag)
      (same-class-name? t "skeptic.analysis.schema.IntersectionT")))

(defn map-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key map-type-tag)
      (same-class-name? t "skeptic.analysis.schema.MapT")))

(defn vector-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key vector-type-tag)
      (same-class-name? t "skeptic.analysis.schema.VectorT")))

(defn set-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key set-type-tag)
      (same-class-name? t "skeptic.analysis.schema.SetT")))

(defn seq-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key seq-type-tag)
      (same-class-name? t "skeptic.analysis.schema.SeqT")))

(defn var-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key var-type-tag)
      (same-class-name? t "skeptic.analysis.schema.VarT")))

(defn placeholder-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key placeholder-type-tag)
      (same-class-name? t "skeptic.analysis.schema.PlaceholderT")))

(defn value-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key value-type-tag)
      (same-class-name? t "skeptic.analysis.schema.ValueT")))

(declare schema->type
         type->schema
         type-compatible-key?
         type-compatible-map-value?)

(defn normalize-type-members
  [members]
  (->> members
       (map schema->type)
       (mapcat (fn [member]
                 (cond
                   (union-type? member) (:members member)
                   :else [member])))
       set))

(defn normalize-intersection-members
  [members]
  (->> members
       (map schema->type)
       (mapcat (fn [member]
                 (cond
                   (intersection-type? member) (:members member)
                   :else [member])))
       set))

(defn union-type
  [members]
  (let [members (normalize-type-members members)]
    (cond
      (empty? members) Dyn
      (= 1 (count members)) (first members)
      :else (->UnionT members))))

(defn intersection-type
  [members]
  (let [members (normalize-intersection-members members)]
    (cond
      (empty? members) Dyn
      (= 1 (count members)) (first members)
      :else (->IntersectionT members))))

(defn type-seq->schema-seq
  [items]
  (doall (map type->schema items)))

(defn broad-dynamic-schema?
  [schema]
  (contains? (set [s/Any
                   s/Num
                   Number
                   java.lang.Number
                   Object
                   java.lang.Object])
             schema))

(defn schema->type
  [schema]
  (let [schema (localize-schema-value schema)]
    (cond
    (dyn-type? schema) schema
    (bottom-type? schema) schema
    (scalar-type? schema) schema
    (fn-method-type? schema) schema
    (fun-type? schema) schema
    (maybe-type? schema) schema
    (union-type? schema) schema
    (intersection-type? schema) schema
    (map-type? schema) schema
    (vector-type? schema) schema
    (set-type? schema) schema
    (seq-type? schema) schema
    (var-type? schema) schema
    (placeholder-type? schema) schema
    (value-type? schema) schema

    :else
    (let [schema (canonicalize-schema schema)]
      (cond
        (nil? schema) (->MaybeT Dyn)
        (= schema Bottom) BottomType
        (placeholder-schema? schema) (->PlaceholderT (placeholder-ref schema))
        (broad-dynamic-schema? schema) Dyn

        (fn-schema? schema)
        (let [{:keys [input-schemas output-schema]} (into {} schema)
              output-type (schema->type output-schema)
              methods (mapv (fn [inputs]
                              (->FnMethodT (mapv (fn [one]
                                                   (let [m (try (into {} one)
                                                                (catch Exception _e {}))]
                                                     (schema->type (or (:schema m) s/Any))))
                                                 inputs)
                                           output-type
                                           (count inputs)
                                           false))
                            input-schemas)]
          (->FunT methods))

        (maybe? schema) (->MaybeT (schema->type (:schema schema)))
        (join? schema) (union-type (:schemas schema))
        (either? schema) (union-type (:schemas schema))
        (conditional-schema? schema) (union-type (map second (:preds-and-schemas schema)))
        (cond-pre? schema) (union-type (:schemas schema))
        (both? schema) (intersection-type (:schemas schema))
        (valued-schema? schema) (->ValueT (schema->type (:schema schema)) (:value schema))
        (variable? schema) (->VarT (schema->type (:schema schema)))

        (plain-map-schema? schema)
        (->MapT (into {}
                       (map (fn [[k v]]
                              [(schema->type k)
                               (schema->type v)]))
                       schema))

        (vector? schema)
        (->VectorT (mapv schema->type schema) (= 1 (count schema)))

        (set? schema)
        (->SetT (into #{} (map schema->type) schema) (= 1 (count schema)))

        (seq? schema)
        (->SeqT (mapv schema->type schema) (= 1 (count schema)))

        :else
        (->ScalarT schema))))))

(defn type->schema
  [type]
  (let [type (schema->type type)]
    (cond
      (dyn-type? type) s/Any
      (bottom-type? type) Bottom
      (scalar-type? type) (:schema type)

      (fun-type? type)
      (let [methods (:methods type)
            output-schema (schema-join (set (map (comp type->schema :output) methods)))]
        (s/make-fn-schema output-schema
                          (mapv (fn [{:keys [inputs]}]
                                  (mapv (fn [idx input]
                                          (s/one (type->schema input)
                                                 (symbol (str "arg" idx))))
                                        (range)
                                        inputs))
                                methods)))

      (maybe-type? type) (s/maybe (type->schema (:inner type)))
      (union-type? type) (schema-join (set (map type->schema (:members type))))
      (intersection-type? type) (apply s/both (map type->schema (:members type)))
      (map-type? type) (into {}
                             (map (fn [[k v]]
                                    [(type->schema k)
                                     (type->schema v)]))
                             (:entries type))
      (vector-type? type) (mapv type->schema (:items type))
      (set-type? type) (into #{} (map type->schema) (:members type))
      (seq-type? type) (type-seq->schema-seq (:items type))
      (var-type? type) (variable (type->schema (:inner type)))
      (placeholder-type? type) (placeholder-schema (:ref type))
      (value-type? type) (valued-schema (type->schema (:inner type))
                                        (:value type))
      :else type)))

(defn derive-schema
  [type]
  (canonicalize-schema (type->schema type)))

(defn derive-output-schema
  [type]
  (canonicalize-output-schema (type->schema type)))

(defn semantic-type-value?
  [value]
  (or (dyn-type? value)
      (bottom-type? value)
      (scalar-type? value)
      (fn-method-type? value)
      (fun-type? value)
      (maybe-type? value)
      (union-type? value)
      (intersection-type? value)
      (map-type? value)
      (vector-type? value)
      (set-type? value)
      (seq-type? value)
      (var-type? value)
      (placeholder-type? value)
      (value-type? value)))

(def derived-type-keys
  [:type
   :node-type
   :output-type
   :expected-argtypes
   :actual-argtypes
   :fn-type])

(defn strip-derived-types
  [entry]
  (cond
    (nil? entry) nil
    (not (map? entry)) entry
    :else
    (let [entry (apply dissoc entry derived-type-keys)]
      (cond-> entry
        (contains? entry :locals) (update :locals (fn [locals]
                                                    (into {}
                                                          (map (fn [[k v]]
                                                                 [k (strip-derived-types v)]))
                                                          locals)))

        (contains? entry :arglists) (update :arglists (fn [arglists]
                                                        (into {}
                                                              (map (fn [[k v]]
                                                                     [k (strip-derived-types v)]))
                                                              arglists)))

        (contains? entry :arg-schema) (update :arg-schema #(mapv strip-derived-types %))
        (contains? entry :params) (update :params #(mapv strip-derived-types %))))))

(defn plain-map-schema?
  [schema]
  (and (map? schema)
       (not (record? schema))
       (not (custom-schema? schema))
       (not (semantic-type-value? schema))
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
      (variable? schema) (variable (semantic-value-schema (:schema schema)))
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

(defn map-key-candidates
  [k]
  (let [groups (exact-key-candidate-groups k)]
    (if (seq groups)
      (->> groups
           (mapcat identity)
           (remove nil?)
           set)
      #{k})))

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

(defn union-like-branches
  [schema]
  (let [schema (canonicalize-schema schema)]
    (cond
      (either? schema) (set (:schemas schema))
      (cond-pre? schema) (set (:schemas schema))
      (conditional-schema? schema) (->> (:preds-and-schemas schema)
                                        (map second)
                                        set)
      :else nil)))

(defn union-like-join
  [schema]
  (when-let [branches (union-like-branches schema)]
    (schema-join branches)))

(defn both-components
  [schema]
  (when-let [schema (and (both? schema)
                         (canonicalize-schema schema))]
    (set (:schemas schema))))

(defn unknown-type?
  [type]
  (let [type (schema->type type)]
    (cond
      (dyn-type? type) true
      (placeholder-type? type) true
      (maybe-type? type) (unknown-type? (:inner type))
      (union-type? type) (some unknown-type? (:members type))
      :else false)))

(defn unknown-schema?
  [schema]
  (unknown-type? (schema->type schema)))

(declare resolve-placeholders)

(defn resolve-placeholders
  [schema resolve-placeholder]
  (let [schema (canonicalize-schema schema)]
    (cond
      (placeholder-schema? schema)
      (canonicalize-schema (or (resolve-placeholder (placeholder-ref schema))
                               schema))

      (bottom-schema? schema)
      Bottom

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

      (variable? schema)
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

(defn polarity->side
  [polarity]
  (case polarity
    :positive :term
    :negative :context
    :global :global
    :none :none
    :term))

(defn flip-polarity
  [polarity]
  (case polarity
    :positive :negative
    :negative :positive
    polarity))

(defn cast-result
  [{:keys [ok? source-type target-type rule polarity reason children details]}]
  (cond-> {:ok? ok?
           :blame-side (if ok? :none (polarity->side polarity))
           :blame-polarity (if ok? :none polarity)
           :rule rule
           :source-type source-type
           :target-type target-type
           :children (vec children)
           :reason reason}
    (map? details) (merge details)))

(defn cast-ok
  ([source-type target-type rule]
   (cast-ok source-type target-type rule [] nil))
  ([source-type target-type rule children]
   (cast-ok source-type target-type rule children nil))
  ([source-type target-type rule children details]
   (cast-result {:ok? true
                 :source-type source-type
                 :target-type target-type
                 :rule rule
                 :polarity :none
                 :children children
                 :details details})))

(defn cast-fail
  ([source-type target-type rule polarity reason]
   (cast-fail source-type target-type rule polarity reason [] nil))
  ([source-type target-type rule polarity reason children]
   (cast-fail source-type target-type rule polarity reason children nil))
  ([source-type target-type rule polarity reason children details]
   (cast-result {:ok? false
                 :source-type source-type
                 :target-type target-type
                 :rule rule
                 :polarity polarity
                 :reason reason
                 :children children
                 :details details})))

(defn all-ok?
  [results]
  (every? :ok? results))

(defn method-accepts-arity?
  [method arity]
  (if (:variadic? method)
    (>= arity (:min-arity method))
    (= arity (:min-arity method))))

(defn matching-source-method
  [source-fun target-method]
  (some #(when (method-accepts-arity? % (count (:inputs target-method)))
           %)
        (:methods source-fun)))

(defn required-map-key-local?
  [k]
  (and (not (s/optional-key? k))
       (or (keyword? k)
           (valued-schema? k)
           (schema? k)
           (map? k))))

(declare check-cast)

(defn map-cast-children
  [source-type target-type opts]
  (let [source-schema (canonicalize-schema (type->schema source-type))
        target-schema (canonicalize-schema (type->schema target-type))
        opt-expected (->> target-schema keys (filter s/optional-key?) (map #(if (s/optional-key? %) (:k %) %)) set)
        required-missing (atom (->> target-schema
                                    keys
                                    (filter required-map-key-local?)
                                    (map #(if (s/optional-key? %) (:k %) %))
                                    set))
        children (reduce (fn [acc [actual-k actual-v]]
                           (let [actual-key-candidates (map-key-candidates actual-k)
                                 ]
                             (if-let [[matched-key matched-value] (matching-map-entry target-schema actual-k)]
                               (let [_ (swap! required-missing #(apply disj % (map-key-candidates matched-key)))
                                     value-result (check-cast (schema->type actual-v)
                                                              (schema->type matched-value)
                                                              opts)
                                     nullable-result (when (and (s/optional-key? actual-k)
                                                                (not-any? #(contains? opt-expected %)
                                                                          actual-key-candidates))
                                                       (cast-fail source-type
                                                                  target-type
                                                                  :map-nullable-key
                                                                  (:polarity opts)
                                                                  :nullable-key
                                                                  []
                                                                  {:actual-key actual-k
                                                                   :expected-key matched-key}))]
                                 (cond-> acc
                                   true (conj value-result)
                                   nullable-result (conj nullable-result)))
                               (conj acc
                                     (cast-fail source-type
                                                target-type
                                                :map-unexpected-key
                                                (:polarity opts)
                                                :unexpected-key
                                                []
                                                {:actual-key actual-k})))))
                         []
                         source-schema)]
    (into children
          (map (fn [missing-k]
                 (cast-fail source-type
                            target-type
                            :map-missing-key
                            (:polarity opts)
                            :missing-key
                            []
                            {:expected-key missing-k})))
          @required-missing)))

(defn collection-cast-children
  [source-items target-items opts]
  (mapv #(check-cast %1 %2 opts) source-items target-items))

(defn set-cast-children
  [source-members target-members opts]
  (reduce (fn [acc source-member]
            (if-let [match (some (fn [target-member]
                                   (let [result (check-cast source-member target-member opts)]
                                     (when (:ok? result)
                                       result)))
                                 target-members)]
              (conj acc match)
              (conj acc
                    (cast-fail source-member
                               (or (first target-members) Dyn)
                               :set-element
                               (:polarity opts)
                               :element-mismatch))))
          []
          source-members))

(defn scalar-cast-fallback?
  [source-type target-type]
  (let [source-schema (canonicalize-schema (type->schema source-type))
        target-schema (canonicalize-schema (type->schema target-type))]
    (or (= (check-if-schema target-schema source-schema) ::schema-valid)
        (= (check-if-schema target-schema (type->schema source-type)) ::schema-valid))))

(defn check-cast
  ([source-type target-type]
   (check-cast source-type target-type {}))
  ([source-type target-type {:keys [polarity] :or {polarity :positive} :as opts}]
   (let [source-type (schema->type source-type)
         target-type (schema->type target-type)
         opts (assoc opts :polarity polarity)]
     (cond
       (bottom-type? source-type)
       (cast-ok source-type target-type :bottom-source)

       (= source-type target-type)
       (cast-ok source-type target-type :exact)

       (dyn-type? target-type)
       (cast-ok source-type target-type :target-dyn)

       (union-type? target-type)
       (let [children (mapv #(check-cast source-type % opts) (:members target-type))]
         (if-let [success (some #(when (:ok? %) %) children)]
           (cast-ok source-type target-type :target-union children {:chosen-rule (:rule success)})
           (cast-fail source-type target-type :target-union polarity :no-union-branch children)))

       (union-type? source-type)
       (let [children (mapv #(check-cast % target-type opts) (:members source-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :source-union children)
           (cast-fail source-type target-type :source-union polarity :source-branch-failed children)))

       (intersection-type? target-type)
       (let [children (mapv #(check-cast source-type % opts) (:members target-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :target-intersection children)
           (cast-fail source-type target-type :target-intersection polarity :target-component-failed children)))

       (intersection-type? source-type)
       (let [children (mapv #(check-cast % target-type opts) (:members source-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :source-intersection children)
           (cast-fail source-type target-type :source-intersection polarity :source-component-failed children)))

       (value-type? source-type)
       (let [value-schema (:value source-type)
             value-match (or (schema-equivalent? (type->schema target-type) value-schema)
                             (= (check-if-schema (type->schema target-type) value-schema) ::schema-valid))]
         (if value-match
           (cast-ok source-type target-type :value-exact)
           (check-cast (:inner source-type) target-type opts)))

       (value-type? target-type)
       (let [expected-value (:value target-type)
             source-schema (type->schema source-type)]
         (if (or (schema-equivalent? source-schema expected-value)
                 (= (check-if-schema source-schema expected-value) ::schema-valid))
           (cast-ok source-type target-type :target-value)
           (check-cast source-type (:inner target-type) opts)))

       (and (maybe-type? source-type) (maybe-type? target-type))
       (let [child (check-cast (:inner source-type) (:inner target-type) opts)]
         (if (:ok? child)
           (cast-ok source-type target-type :maybe-both [child])
           (cast-fail source-type target-type :maybe-both polarity :maybe-inner-failed [child])))

       (maybe-type? target-type)
       (let [child (check-cast source-type (:inner target-type) opts)]
         (if (:ok? child)
           (cast-ok source-type target-type :maybe-target [child])
           (cast-fail source-type target-type :maybe-target polarity :maybe-target-inner-failed [child])))

       (maybe-type? source-type)
       (cast-fail source-type target-type :maybe-source polarity :nullable-source)

       (var-type? source-type)
       (check-cast (:inner source-type) target-type opts)

       (var-type? target-type)
       (check-cast source-type (:inner target-type) opts)

       (and (fun-type? source-type) (fun-type? target-type))
       (let [children (mapv (fn [target-method]
                              (if-let [source-method (matching-source-method source-type target-method)]
                                (let [domain-results (mapv (fn [target-input source-input]
                                                             (check-cast target-input
                                                                         source-input
                                                                         (update opts :polarity flip-polarity)))
                                                           (:inputs target-method)
                                                           (:inputs source-method))
                                      range-result (check-cast (:output source-method)
                                                               (:output target-method)
                                                               opts)
                                      method-children (conj domain-results range-result)]
                                  (if (all-ok? method-children)
                                    (cast-ok source-method target-method :function-method method-children)
                                    (cast-fail source-method target-method :function-method polarity :function-component-failed method-children)))
                                (cast-fail source-type
                                           target-type
                                           :function-arity
                                           polarity
                                           :arity-mismatch
                                           []
                                           {:target-method target-method})))
                            (:methods target-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :function children)
           (cast-fail source-type target-type :function polarity :function-cast-failed children)))

       (and (map-type? source-type) (map-type? target-type))
       (let [children (map-cast-children source-type target-type opts)]
         (if (all-ok? children)
           (cast-ok source-type target-type :map children)
           (cast-fail source-type target-type :map polarity :map-cast-failed children)))

       (and (vector-type? source-type) (vector-type? target-type))
       (let [source-items (:items source-type)
             target-items (:items target-type)]
         (if (= (count source-items) (count target-items))
           (let [children (collection-cast-children source-items target-items opts)]
             (if (all-ok? children)
               (cast-ok source-type target-type :vector children)
               (cast-fail source-type target-type :vector polarity :vector-element-failed children)))
           (cast-fail source-type target-type :vector polarity :vector-arity-mismatch)))

       (and (seq-type? source-type) (seq-type? target-type))
       (let [source-items (:items source-type)
             target-items (:items target-type)]
         (if (= (count source-items) (count target-items))
           (let [children (collection-cast-children source-items target-items opts)]
             (if (all-ok? children)
               (cast-ok source-type target-type :seq children)
               (cast-fail source-type target-type :seq polarity :seq-element-failed children)))
           (cast-fail source-type target-type :seq polarity :seq-arity-mismatch)))

       (and (set-type? source-type) (set-type? target-type))
       (let [source-members (:members source-type)
             target-members (:members target-type)]
         (if (= (count source-members) (count target-members))
           (let [children (set-cast-children source-members target-members opts)]
             (if (all-ok? children)
               (cast-ok source-type target-type :set children)
               (cast-fail source-type target-type :set polarity :set-element-failed children)))
           (cast-fail source-type target-type :set polarity :set-cardinality-mismatch)))

       (or (dyn-type? source-type)
           (placeholder-type? source-type))
       (cast-ok source-type target-type :residual-dynamic)

       (scalar-cast-fallback? source-type target-type)
       (cast-ok source-type target-type :scalar-fallback)

       :else
       (cast-fail source-type target-type :mismatch polarity :mismatch)))))

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

(defn schema-compatible?
  [expected actual]
  (:ok? (check-cast (schema->type actual) (schema->type expected))))

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
