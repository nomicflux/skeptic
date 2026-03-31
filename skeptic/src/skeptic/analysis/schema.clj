(ns skeptic.analysis.schema
  (:require [schema.core :as s])
  (:import [schema.core Both CondPre ConditionalSchema Constrained Either EnumSchema EqSchema FnSchema Maybe NamedSchema One Schema]))

(def custom-schema-tag-key
  ::custom-schema)

(def semantic-type-tag-key
  ::semantic-type)

(def ^:dynamic *error-context*
  nil)

(defn compact-context-map
  [m]
  (into {}
        (remove (comp nil? val))
        m))

(defmacro with-error-context
  [context & body]
  `(binding [*error-context* (merge *error-context*
                                    (compact-context-map ~context))]
     ~@body))

(defn error-location-text
  [{:keys [file line column]}]
  (cond
    (and file line column) (str file ":" line ":" column)
    (and file line) (str file ":" line)
    file file
    line (str line)
    :else nil))

(defn ambiguous-map-entry-ex-info
  [actual-key entries ambiguous]
  (let [context (compact-context-map *error-context*)
        location-text (some-> context :location error-location-text)
        expr-text (or (:source-expression context)
                      (some-> (:expr context) pr-str))
        message (cond-> (format "Multiple results for key %s and m %s: %s"
                                actual-key
                                entries
                                (mapv :key ambiguous))
                  location-text (str "\nLocation: " location-text)
                  expr-text (str "\nExpression: " expr-text))]
    (ex-info message
             (merge {:error ::ambiguous-map-entry
                     :actual-key actual-key
                     :entries entries
                     :ambiguous-keys (mapv :key ambiguous)}
                    (when (seq context)
                      {:context context})))))

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
         render-schema-form
         render-schema
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
         ground-type?
         refinement-type?
         adapter-leaf-type?
         optional-key-type?
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
         type-var-type?
         forall-type?
         sealed-dyn-type?
         placeholder-type?
         value-type?
         semantic-type-value?
         render-type-form
         render-type
         matches-map
         plain-map-schema?)

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

(defn schema?
  [s]
  (let [s (localize-schema-value s)]
    (cond
      (nil? s) true
      (schema-literal? s) true
      (custom-schema? s) true
      (instance? Schema s) true
      (class? s) true
      (s/optional-key? s) (schema? (:k s))
      (instance? One s) (let [m (try (into {} s)
                                     (catch Exception _e nil))]
                          (and (map? m)
                               (schema? (:schema m))))
      (and (map? s)
           (not (record? s))
           (not (semantic-type-value? s)))
      (every? (fn [[k v]]
                (and (schema? k)
                     (schema? v)))
              s)
      (vector? s) (every? schema? s)
      (set? s) (and (= 1 (count s))
                    (every? schema? s))
      (seq? s) (every? schema? s)
      :else false)))

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

(defn de-enum
  [s]
  (cond-> s
    (enum-schema? s)
    :vs))

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

    (schema-literal? schema)
    schema

    (s/optional-key? schema)
    (list 'optional-key (schema-explain (:k schema)))

    (instance? One schema)
    (let [m (try (into {} schema)
                 (catch Exception _e nil))]
      (if (map? m)
        (list 'one
              (schema-explain (:schema m))
              (:name m))
        schema))

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

(defn qualified-var-symbol
  [v]
  (let [{:keys [ns name]} (meta v)]
    (when (and ns name)
      (symbol (str (ns-name ns) "/" name)))))

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
    (if (and (map? m)
             (contains? m :schema))
      (s/one (canonicalize-schema (:schema m))
             (:name m))
      (canonicalize-schema one))))

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
  (canonicalize-schema* schema {:constrained->base? false}))

(def dyn-type-tag
  ::dyn-type)

(def bottom-type-tag
  ::bottom-type)

(def ground-type-tag
  ::ground-type)

(def refinement-type-tag
  ::refinement-type)

(def adapter-leaf-type-tag
  ::adapter-leaf-type)

(def optional-key-type-tag
  ::optional-key-type)

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

(def type-var-type-tag
  ::type-var-type)

(def forall-type-tag
  ::forall-type)

(def sealed-dyn-type-tag
  ::sealed-dyn-type)

(defn ->DynT
  []
  {semantic-type-tag-key dyn-type-tag})

(defn ->BottomT
  []
  {semantic-type-tag-key bottom-type-tag})

(defn ->GroundT
  [ground display-form]
  {semantic-type-tag-key ground-type-tag
   :ground ground
   :display-form display-form})

(defn ->RefinementT
  [base display-form accepts? adapter-data]
  {semantic-type-tag-key refinement-type-tag
   :base base
   :display-form display-form
   :accepts? accepts?
   :adapter-data adapter-data})

(defn ->AdapterLeafT
  [adapter display-form accepts? adapter-data]
  {semantic-type-tag-key adapter-leaf-type-tag
   :adapter adapter
   :display-form display-form
   :accepts? accepts?
   :adapter-data adapter-data})

(defn ->OptionalKeyT
  [inner]
  {semantic-type-tag-key optional-key-type-tag
   :inner inner})

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

(defn ->TypeVarT
  [name]
  {semantic-type-tag-key type-var-type-tag
   :name name})

(defn ->ForallT
  [binder body]
  {semantic-type-tag-key forall-type-tag
   :binder binder
   :body body})

(defn ->SealedDynT
  [ground]
  {semantic-type-tag-key sealed-dyn-type-tag
   :ground ground})

(def Dyn
  (->DynT))

(def BottomType
  (->BottomT))

(declare localize-schema-value*)

(defn localize-schema-value
  [value]
  (localize-schema-value* value #{}))

(defn localize-schema-value*
  [value seen-vars]
  (cond
    (nil? value) nil
    (same-class-name? value "clojure.lang.Var$Unbound")
    (localize-schema-value* (read-instance-field value "v") seen-vars)
    (instance? clojure.lang.Var value)
    (let [var-ref (or (qualified-var-symbol value) value)]
      (cond
        (contains? seen-vars var-ref) (placeholder-schema var-ref)
        (bound? value) (localize-schema-value* @value (conj seen-vars var-ref))
        :else (placeholder-schema var-ref)))
    (bottom-schema? value) Bottom
    (join? value)
    (apply join (map #(localize-schema-value* % seen-vars) (:schemas value)))
    (same-class-name? value "skeptic.analysis.schema.Join")
    (apply join (map #(localize-schema-value* % seen-vars)
                     (read-instance-field value "schemas")))
    (valued-schema? value)
    (valued-schema (localize-schema-value* (:schema value) seen-vars)
                   (localize-schema-value* (:value value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.ValuedSchema")
    (valued-schema (localize-schema-value* (read-instance-field value "schema") seen-vars)
                   (localize-schema-value* (read-instance-field value "value") seen-vars))
    (variable? value)
    (variable (localize-schema-value* (:schema value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.Variable")
    (variable (localize-schema-value* (read-instance-field value "schema") seen-vars))
    (dyn-type? value) Dyn
    (bottom-type? value) BottomType
    (ground-type? value)
    (->GroundT (:ground value) (:display-form value))
    (same-class-name? value "skeptic.analysis.schema.GroundT")
    (->GroundT (read-instance-field value "ground")
               (read-instance-field value "display_form"))
    (refinement-type? value)
    (->RefinementT (localize-schema-value* (:base value) seen-vars)
                   (:display-form value)
                   (:accepts? value)
                   (localize-schema-value* (:adapter-data value) seen-vars))
    (adapter-leaf-type? value)
    (->AdapterLeafT (:adapter value)
                    (:display-form value)
                    (:accepts? value)
                    (localize-schema-value* (:adapter-data value) seen-vars))
    (optional-key-type? value)
    (->OptionalKeyT (localize-schema-value* (:inner value) seen-vars))
    (fn-method-type? value)
    (->FnMethodT (localize-schema-value* (:inputs value) seen-vars)
                 (localize-schema-value* (:output value) seen-vars)
                 (:min-arity value)
                 (:variadic? value))
    (same-class-name? value "skeptic.analysis.schema.FnMethodT")
    (->FnMethodT (localize-schema-value* (read-instance-field value "inputs") seen-vars)
                 (localize-schema-value* (read-instance-field value "output") seen-vars)
                 (read-instance-field value "min_arity")
                 (read-instance-field value "variadic_QMARK_"))
    (fun-type? value)
    (->FunT (localize-schema-value* (:methods value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.FunT")
    (->FunT (localize-schema-value* (read-instance-field value "methods") seen-vars))
    (maybe-type? value)
    (->MaybeT (localize-schema-value* (:inner value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.MaybeT")
    (->MaybeT (localize-schema-value* (read-instance-field value "inner") seen-vars))
    (union-type? value)
    (->UnionT (localize-schema-value* (:members value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.UnionT")
    (->UnionT (localize-schema-value* (read-instance-field value "members") seen-vars))
    (intersection-type? value)
    (->IntersectionT (localize-schema-value* (:members value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.IntersectionT")
    (->IntersectionT (localize-schema-value* (read-instance-field value "members") seen-vars))
    (map-type? value)
    (->MapT (localize-schema-value* (:entries value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.MapT")
    (->MapT (localize-schema-value* (read-instance-field value "entries") seen-vars))
    (vector-type? value)
    (->VectorT (localize-schema-value* (:items value) seen-vars)
               (:homogeneous? value))
    (same-class-name? value "skeptic.analysis.schema.VectorT")
    (->VectorT (localize-schema-value* (read-instance-field value "items") seen-vars)
               (read-instance-field value "homogeneous_QMARK_"))
    (set-type? value)
    (->SetT (localize-schema-value* (:members value) seen-vars)
            (:homogeneous? value))
    (same-class-name? value "skeptic.analysis.schema.SetT")
    (->SetT (localize-schema-value* (read-instance-field value "members") seen-vars)
            (read-instance-field value "homogeneous_QMARK_"))
    (seq-type? value)
    (->SeqT (localize-schema-value* (:items value) seen-vars)
            (:homogeneous? value))
    (same-class-name? value "skeptic.analysis.schema.SeqT")
    (->SeqT (localize-schema-value* (read-instance-field value "items") seen-vars)
            (read-instance-field value "homogeneous_QMARK_"))
    (var-type? value)
    (->VarT (localize-schema-value* (:inner value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.VarT")
    (->VarT (localize-schema-value* (read-instance-field value "inner") seen-vars))
    (placeholder-type? value)
    (->PlaceholderT (localize-schema-value* (:ref value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.PlaceholderT")
    (->PlaceholderT (localize-schema-value* (read-instance-field value "ref") seen-vars))
    (value-type? value)
    (->ValueT (localize-schema-value* (:inner value) seen-vars)
              (localize-schema-value* (:value value) seen-vars))
    (same-class-name? value "skeptic.analysis.schema.ValueT")
    (->ValueT (localize-schema-value* (read-instance-field value "inner") seen-vars)
              (localize-schema-value* (read-instance-field value "value") seen-vars))
    (type-var-type? value)
    (->TypeVarT (:name value))
    (forall-type? value)
    (->ForallT (:binder value)
               (localize-schema-value* (:body value) seen-vars))
    (sealed-dyn-type? value)
    (->SealedDynT (localize-schema-value* (:ground value) seen-vars))
    (vector? value) (mapv #(localize-schema-value* % seen-vars) value)
    (set? value) (into #{} (map #(localize-schema-value* % seen-vars)) value)
    (and (map? value) (not (record? value)))
    (into {}
          (map (fn [[k v]]
                 [(localize-schema-value* k seen-vars)
                  (localize-schema-value* v seen-vars)]))
          value)
    (seq? value) (doall (map #(localize-schema-value* % seen-vars) value))
    :else value))

(defn dyn-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key dyn-type-tag)
      (same-class-name? t "skeptic.analysis.schema.DynT")))

(defn bottom-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key bottom-type-tag)
      (same-class-name? t "skeptic.analysis.schema.BottomT")))

(defn ground-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key ground-type-tag)
      (same-class-name? t "skeptic.analysis.schema.GroundT")))

(defn refinement-type?
  [t]
  (tagged-map? t semantic-type-tag-key refinement-type-tag))

(defn adapter-leaf-type?
  [t]
  (tagged-map? t semantic-type-tag-key adapter-leaf-type-tag))

(defn optional-key-type?
  [t]
  (tagged-map? t semantic-type-tag-key optional-key-type-tag))

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

(defn type-var-type?
  [t]
  (tagged-map? t semantic-type-tag-key type-var-type-tag))

(defn forall-type?
  [t]
  (tagged-map? t semantic-type-tag-key forall-type-tag))

(defn sealed-dyn-type?
  [t]
  (tagged-map? t semantic-type-tag-key sealed-dyn-type-tag))

(defn placeholder-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key placeholder-type-tag)
      (same-class-name? t "skeptic.analysis.schema.PlaceholderT")))

(defn value-type?
  [t]
  (or (tagged-map? t semantic-type-tag-key value-type-tag)
      (same-class-name? t "skeptic.analysis.schema.ValueT")))

(declare schema->type
         check-cast
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

(defn broad-dynamic-schema?
  [schema]
  (contains? (set [s/Any
                   s/Num
                   Number
                   java.lang.Number
                   Object
                   java.lang.Object])
             schema))

(defn render-schema-form
  [schema]
  (let [schema (canonicalize-schema schema)]
    (when-not (schema? schema)
      (throw (IllegalArgumentException.
              (format "Not a valid Schema-domain value: %s" (pr-str schema)))))
    (schema-explain schema)))

(defn render-schema
  [schema]
  (some-> schema
          render-schema-form
          pr-str))

(defn import-display-form
  [schema]
  (render-schema-form schema))

(defn primitive-ground-type
  [schema]
  (let [schema (canonical-scalar-schema schema)]
    (cond
      (= schema s/Int) (->GroundT :int 'Int)
      (= schema s/Str) (->GroundT :str 'Str)
      (= schema s/Keyword) (->GroundT :keyword 'Keyword)
      (= schema s/Symbol) (->GroundT :symbol 'Symbol)
      (= schema s/Bool) (->GroundT :bool 'Bool)
      (and (class? schema)
           (not (broad-dynamic-schema? schema)))
      (->GroundT {:class schema} (schema-explain schema))
      :else nil)))

(defn literal-ground-type
  [value]
  (cond
    (integer? value) (->GroundT :int 'Int)
    (string? value) (->GroundT :str 'Str)
    (keyword? value) (->GroundT :keyword 'Keyword)
    (symbol? value) (->GroundT :symbol 'Symbol)
    (boolean? value) (->GroundT :bool 'Bool)
    :else nil))

(defn exact-value-import-type
  [value]
  (->ValueT (or (literal-ground-type value) Dyn) value))

(defn refinement-import-type
  [schema]
  (->RefinementT (schema->type (de-constrained schema))
                 (import-display-form schema)
                 (fn [value]
                   (= (check-if-schema schema value) ::schema-valid))
                 {:adapter :schema
                  :kind :constrained}))

(defn adapter-leaf-import-type
  [schema]
  (->AdapterLeafT :schema
                  (import-display-form schema)
                  (fn [value]
                    (= (check-if-schema schema value) ::schema-valid))
                  {:source-schema schema}))

(defn schema->type
  [schema]
  (let [schema (localize-schema-value schema)]
    (cond
    (dyn-type? schema) schema
    (bottom-type? schema) schema
    (ground-type? schema) schema
    (refinement-type? schema) schema
    (adapter-leaf-type? schema) schema
    (optional-key-type? schema) schema
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
    (type-var-type? schema) schema
    (forall-type? schema) schema
    (sealed-dyn-type? schema) schema
    (placeholder-type? schema) schema
    (value-type? schema) schema

    :else
    (let [schema (canonicalize-schema schema)]
      (cond
        (nil? schema) (->MaybeT Dyn)
        (= schema Bottom) BottomType
        (placeholder-schema? schema) (->PlaceholderT (placeholder-ref schema))
        (broad-dynamic-schema? schema) Dyn
        (instance? One schema) (schema->type (or (:schema (try (into {} schema)
                                                              (catch Exception _e {})))
                                                s/Any))
        (schema-literal? schema) (exact-value-import-type schema)
        (s/optional-key? schema) (->OptionalKeyT (schema->type (:k schema)))
        (eq? schema) (exact-value-import-type (de-eq schema))
        (constrained? schema) (refinement-import-type schema)
        (primitive-ground-type schema) (primitive-ground-type schema)

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
        (enum-schema? schema) (union-type (map exact-value-import-type (de-enum schema)))
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
        (adapter-leaf-import-type schema))))))

(defn semantic-type-value?
  [value]
  (or (dyn-type? value)
      (bottom-type? value)
      (ground-type? value)
      (refinement-type? value)
      (adapter-leaf-type? value)
      (optional-key-type? value)
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
      (type-var-type? value)
      (forall-type? value)
      (sealed-dyn-type? value)
      (placeholder-type? value)
      (value-type? value)))

(declare render-type-form)

(defn render-fn-input-form
  [method]
  (let [inputs (mapv render-type-form (:inputs method))]
    (if (:variadic? method)
      (concat (take (:min-arity method) inputs)
              ['& (drop (:min-arity method) inputs)])
      inputs)))

(defn render-type-form
  [type]
  (let [type (schema->type type)]
    (cond
      (dyn-type? type) 'Any
      (bottom-type? type) 'Bottom
      (ground-type? type) (:display-form type)
      (refinement-type? type) (:display-form type)
      (adapter-leaf-type? type) (:display-form type)
      (optional-key-type? type) (list 'optional-key (render-type-form (:inner type)))
      (value-type? type) (:value type)
      (type-var-type? type) (:name type)
      (forall-type? type) (list 'forall (:binder type) (render-type-form (:body type)))
      (sealed-dyn-type? type) (list 'sealed (render-type-form (:ground type)))
      (fn-method-type? type) (list* '=> (render-type-form (:output type)) (render-fn-input-form type))
      (fun-type? type)
      (if (= 1 (count (:methods type)))
        (render-type-form (first (:methods type)))
        (list* '=>* (map render-type-form (:methods type))))
      (maybe-type? type) (list 'maybe (render-type-form (:inner type)))
      (union-type? type) (list* 'union (map render-type-form (sort-by pr-str (:members type))))
      (intersection-type? type) (list* 'intersection (map render-type-form (sort-by pr-str (:members type))))
      (map-type? type)
      (into {}
            (map (fn [[k v]]
                   [(render-type-form k)
                    (render-type-form v)]))
            (:entries type))
      (vector-type? type) (mapv render-type-form (:items type))
      (set-type? type) (into #{} (map render-type-form) (:members type))
      (seq-type? type) (doall (map render-type-form (:items type)))
      (var-type? type) (list 'var (render-type-form (:inner type)))
      :else type)))

(defn render-type
  [type]
  (some-> type
          render-type-form
          pr-str))

(defn type-var-name
  [type]
  (when (type-var-type? type)
    (:name type)))

(defn type-free-vars
  [type]
  (let [type (schema->type type)]
    (cond
      (or (dyn-type? type)
          (bottom-type? type)
          (ground-type? type)
          (refinement-type? type)
          (adapter-leaf-type? type)
          (optional-key-type? type)
          (placeholder-type? type))
      #{}

      (fn-method-type? type)
      (into (type-free-vars (:output type))
            (mapcat type-free-vars (:inputs type)))

      (fun-type? type)
      (into #{} (mapcat type-free-vars (:methods type)))

      (maybe-type? type)
      (type-free-vars (:inner type))

      (union-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (intersection-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (map-type? type)
      (reduce (fn [acc [k v]]
                (into acc (concat (type-free-vars k)
                                  (type-free-vars v))))
              #{}
              (:entries type))

      (vector-type? type)
      (into #{} (mapcat type-free-vars (:items type)))

      (set-type? type)
      (into #{} (mapcat type-free-vars (:members type)))

      (seq-type? type)
      (into #{} (mapcat type-free-vars (:items type)))

      (var-type? type)
      (type-free-vars (:inner type))

      (value-type? type)
      (type-free-vars (:inner type))

      (type-var-type? type)
      #{(:name type)}

      (forall-type? type)
      (disj (type-free-vars (:body type)) (:binder type))

      (sealed-dyn-type? type)
      (type-free-vars (:ground type))

      :else
      #{})))

(defn type-substitute
  [type binder replacement]
  (let [type (schema->type type)
        replacement (schema->type replacement)]
    (cond
      (or (dyn-type? type)
          (bottom-type? type)
          (ground-type? type)
          (refinement-type? type)
          (adapter-leaf-type? type)
          (placeholder-type? type))
      type

      (optional-key-type? type)
      (->OptionalKeyT (type-substitute (:inner type) binder replacement))

      (fn-method-type? type)
      (->FnMethodT (mapv #(type-substitute % binder replacement) (:inputs type))
                   (type-substitute (:output type) binder replacement)
                   (:min-arity type)
                   (:variadic? type))

      (fun-type? type)
      (->FunT (mapv #(type-substitute % binder replacement) (:methods type)))

      (maybe-type? type)
      (->MaybeT (type-substitute (:inner type) binder replacement))

      (union-type? type)
      (->UnionT (set (map #(type-substitute % binder replacement) (:members type))))

      (intersection-type? type)
      (->IntersectionT (set (map #(type-substitute % binder replacement) (:members type))))

      (map-type? type)
      (->MapT (into {}
                     (map (fn [[k v]]
                            [(type-substitute k binder replacement)
                             (type-substitute v binder replacement)]))
                     (:entries type)))

      (vector-type? type)
      (->VectorT (mapv #(type-substitute % binder replacement) (:items type))
                 (:homogeneous? type))

      (set-type? type)
      (->SetT (set (map #(type-substitute % binder replacement) (:members type)))
               (:homogeneous? type))

      (seq-type? type)
      (->SeqT (mapv #(type-substitute % binder replacement) (:items type))
              (:homogeneous? type))

      (var-type? type)
      (->VarT (type-substitute (:inner type) binder replacement))

      (value-type? type)
      (->ValueT (type-substitute (:inner type) binder replacement)
                (:value type))

      (type-var-type? type)
      (if (= binder (:name type))
        replacement
        type)

      (forall-type? type)
      (if (= binder (:binder type))
        type
        (->ForallT (:binder type)
                   (type-substitute (:body type) binder replacement)))

      (sealed-dyn-type? type)
      (->SealedDynT (type-substitute (:ground type) binder replacement))

      :else
      type)))

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
         valued-compatible?
         matching-map-entry)

(defn nested-value-compatible?
  [expected actual]
  (let [actual (canonicalize-schema actual)]
    (if (valued-schema? actual)
      (or (schema-compatible? expected (:value actual))
          (schema-compatible? expected (:schema actual)))
      (schema-compatible? expected actual))))

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
       (if-let [{:keys [value kind]} (matching-map-entry m key)]
         (let [base-value (semantic-value-schema value)
               base-value (if (and (= kind :optional-explicit)
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
      (join? schema) (set (:schemas schema))
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

(defn ensure-cast-state
  [cast-state]
  (merge {:nu-bindings []
          :abstract-vars #{}
          :active-seals #{}}
         cast-state))

(defn cast-state
  [opts]
  (ensure-cast-state (:cast-state opts)))

(defn with-abstract-var
  [opts binder]
  (assoc opts :cast-state (update (cast-state opts) :abstract-vars conj binder)))

(defn with-nu-binding
  [opts binder witness-type]
  (assoc opts :cast-state (-> (cast-state opts)
                              (update :nu-bindings conj {:type-var binder
                                                         :witness-type (schema->type witness-type)})
                              (update :abstract-vars conj binder))))

(defn register-seal
  [opts sealed-type]
  (assoc opts :cast-state (update (cast-state opts) :active-seals conj (schema->type sealed-type))))

(defn sealed-ground-name
  [type]
  (some-> type schema->type :ground type-var-name))

(defn contains-sealed-ground?
  [type binder]
  (let [type (schema->type type)]
    (cond
      (sealed-dyn-type? type)
      (= binder (sealed-ground-name type))

      (fn-method-type? type)
      (or (contains-sealed-ground? (:output type) binder)
          (some #(contains-sealed-ground? % binder) (:inputs type)))

      (fun-type? type)
      (some #(contains-sealed-ground? % binder) (:methods type))

      (maybe-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (or (union-type? type)
          (intersection-type? type))
      (some #(contains-sealed-ground? % binder) (:members type))

      (map-type? type)
      (some (fn [[k v]]
              (or (contains-sealed-ground? k binder)
                  (contains-sealed-ground? v binder)))
            (:entries type))

      (or (vector-type? type)
          (seq-type? type))
      (some #(contains-sealed-ground? % binder) (:items type))

      (set-type? type)
      (some #(contains-sealed-ground? % binder) (:members type))

      (var-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (value-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (forall-type? type)
      (and (not= binder (:binder type))
           (contains-sealed-ground? (:body type) binder))

      :else
      false)))

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

(defn with-cast-path
  [result segment]
  (cond-> result
    (some? segment) (update :path (fnil conj []) segment)))

(defn indexed-cast-children
  [segment-kind build-child xs]
  (mapv (fn [idx x]
          (with-cast-path (build-child x)
            {:kind segment-kind
             :index idx}))
        (range)
        xs))

(defn all-ok?
  [results]
  (every? :ok? results))

(defn check-type-test
  ([value-type ground-type]
   (check-type-test value-type ground-type {}))
  ([value-type ground-type opts]
   (let [value-type (schema->type value-type)
         ground-type (schema->type ground-type)]
     (if (sealed-dyn-type? value-type)
       (cast-fail value-type
                  ground-type
                  :is-tamper
                  :global
                  :is-tamper
                  []
                  {:cast-state (cast-state opts)})
       (cast-ok value-type
                ground-type
                :dynamic-test
                []
                {:matches? (= value-type ground-type)
                 :cast-state (cast-state opts)})))))

(defn exit-nu-scope
  ([type binder]
   (exit-nu-scope type binder {}))
  ([type binder opts]
   (let [type (schema->type type)
         binder (or (type-var-name (schema->type binder))
                    binder)]
     (if (contains-sealed-ground? type binder)
       (cast-fail type
                  (->TypeVarT binder)
                  :nu-tamper
                  :global
                  :nu-tamper
                  []
                  {:cast-state (cast-state opts)})
       (cast-ok type
                (->TypeVarT binder)
                :nu-pass
                []
                {:cast-state (cast-state opts)})))))

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

(defn optional-key-inner
  [type]
  (if (optional-key-type? type)
    (:inner type)
    type))

(defn exact-value-type?
  [type]
  (value-type? (schema->type type)))

(def map-entry-kind-order
  {:required-explicit 0
   :optional-explicit 1
   :extra-schema 2})

(defn map-entry-kind
  ([entry-key]
   (let [entry-key (canonicalize-schema entry-key)]
     (cond
       (and (not (semantic-type-value? entry-key))
            (s/optional-key? entry-key))
       :optional-explicit

       (and (not (semantic-type-value? entry-key))
            (s/specific-key? entry-key))
       :required-explicit

       :else
       (let [entry-type (schema->type entry-key)
             inner (optional-key-inner entry-type)]
         (cond
           (and (optional-key-type? entry-type)
                (exact-value-type? inner))
           :optional-explicit

           (exact-value-type? inner)
           :required-explicit

           :else
           :extra-schema)))))
  ([entries entry-key]
   (let [entries (canonicalize-schema entries)
         entry-key (canonicalize-schema entry-key)
         typed-entries? (every? semantic-type-value? (keys entries))]
     (if (and (plain-map-schema? entries)
              (not typed-entries?))
       (let [extra-key (s/find-extra-keys-schema entries)]
         (if (= entry-key extra-key)
           :extra-schema
           (map-entry-kind entry-key)))
       (map-entry-kind entry-key)))))

(defn required-map-key-type?
  [type]
  (= :required-explicit (map-entry-kind type)))

(defn path-key
  [type]
  (let [type (optional-key-inner type)]
    (when (exact-value-type? type)
      (:value type))))

(defn with-map-path
  [cast-result key]
  (if-let [path-value (path-key key)]
    (with-cast-path cast-result
      {:kind :map-key
       :key path-value})
    cast-result))

(defn type-compatible-key?
  [actual-key target-key]
  (:ok? (check-cast (optional-key-inner actual-key)
                    (optional-key-inner target-key))))

(defn map-entry-rank
  [entries entry-key]
  [(get map-entry-kind-order (map-entry-kind entries entry-key) 99)])

(defn matching-map-entry
  [entries actual-key]
  (let [entries (canonicalize-schema entries)
        actual-key-type (schema->type actual-key)
        matches (->> entries
                     (map (fn [[entry-key entry-value]]
                            (when (type-compatible-key? actual-key-type (schema->type entry-key))
                              {:key entry-key
                               :value entry-value
                               :kind (map-entry-kind entries entry-key)})))
                     (remove nil?)
                     (sort-by (fn [{:keys [key]}]
                                (map-entry-rank entries key))))]
    (when-let [matched-entry (first matches)]
      (let [best-rank (map-entry-rank entries (:key matched-entry))
            ambiguous (->> matches
                           (take-while #(= best-rank
                                           (map-entry-rank entries (:key %))))
                           vec)]
        (when (> (count ambiguous) 1)
          (throw (ambiguous-map-entry-ex-info actual-key entries ambiguous)))
        matched-entry))))

(defn map-contains-key-classification
  [type key]
  (let [key-type (exact-value-import-type key)
        entries (:entries (schema->type type))]
    (if-let [{:keys [kind]} (matching-map-entry entries key-type)]
      (if (= kind :required-explicit)
        :always
        :unknown)
      :never)))

(defn contains-key-classification
  [schema key]
  (let [type (schema->type schema)]
    (cond
      (bottom-type? type) :never

      (maybe-type? type)
      (case (contains-key-classification (:inner type) key)
        :never :never
        :always :unknown
        :unknown :unknown)

      (map-type? type)
      (map-contains-key-classification type key)

      :else
      :unknown)))

(defn refine-schema-by-contains-key
  [schema key polarity]
  (let [schema (canonicalize-schema schema)
        branches (or (union-like-branches schema) #{schema})
        kept (->> branches
                  (keep (fn [branch]
                          (let [classification (contains-key-classification branch key)]
                            (case [polarity classification]
                              [true :never] nil
                              [false :always] nil
                              branch))))
                  set)]
    (cond
      (empty? kept) Bottom
      (= 1 (count kept)) (first kept)
      :else (schema-join kept))))

(defn ground-accepts-value?
  [type value]
  (let [ground (:ground (schema->type type))]
    (cond
      (= ground :int) (integer? value)
      (= ground :str) (string? value)
      (= ground :keyword) (keyword? value)
      (= ground :symbol) (symbol? value)
      (= ground :bool) (boolean? value)
      (and (map? ground) (:class ground)) (instance? (:class ground) value)
      :else false)))

(declare value-satisfies-type?)

(defn leaf-overlap?
  [source-type target-type]
  (let [source-type (schema->type source-type)
        target-type (schema->type target-type)]
    (cond
      (ground-type? source-type)
      (cond
        (ground-type? target-type)
        (let [s (:ground source-type)
              t (:ground target-type)]
          (cond
            (= s t) true
            (and (map? s) (:class s) (map? t) (:class t))
            (or (.isAssignableFrom ^Class (:class s) ^Class (:class t))
                (.isAssignableFrom ^Class (:class t) ^Class (:class s)))
            :else false))

        (refinement-type? target-type)
        (leaf-overlap? source-type (:base target-type))

        (adapter-leaf-type? target-type)
        true

        :else false)

      (refinement-type? source-type)
      (leaf-overlap? (:base source-type) target-type)

      (adapter-leaf-type? source-type)
      true

      :else false)))

(defn type-compatible-map-value?
  [value-type expected-type]
  (:ok? (check-cast value-type expected-type)))

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
       (let [entries (:entries (schema->type map-type))
             required-missing (atom (->> entries keys (filter required-map-key-type?) set))]
         (and
          (every? (fn [[k v]]
                    (let [actual-key (exact-value-import-type k)]
                      (if-let [{:keys [key value]} (matching-map-entry entries actual-key)]
                        (do
                          (swap! required-missing disj key)
                          (value-satisfies-type? v value))
                        false)))
                  value)
          (empty? @required-missing)))))

(defn value-satisfies-type?
  [value type]
  (let [type (schema->type type)]
    (cond
      (or (dyn-type? type)
          (placeholder-type? type))
      true

      (bottom-type? type)
      true

      (value-type? type)
      (= value (:value type))

      (ground-type? type)
      (ground-accepts-value? type value)

      (refinement-type? type)
      (and (value-satisfies-type? value (:base type))
           ((:accepts? type) value))

      (adapter-leaf-type? type)
      ((:accepts? type) value)

      (optional-key-type? type)
      (value-satisfies-type? value (:inner type))

      (maybe-type? type)
      (or (nil? value)
          (value-satisfies-type? value (:inner type)))

      (union-type? type)
      (some #(value-satisfies-type? value %) (:members type))

      (intersection-type? type)
      (every? #(value-satisfies-type? value %) (:members type))

      (map-type? type)
      (map-value-satisfies-type? value type)

      (vector-type? type)
      (and (vector? value)
           (if (:homogeneous? type)
             (every? #(value-satisfies-type? % (or (first (:items type)) Dyn))
                     value)
             (and (= (count value) (count (:items type)))
                  (every? true? (map value-satisfies-type? value (:items type))))))

      (seq-type? type)
      (and (sequential? value)
           (= (count value) (count (:items type)))
           (every? true? (map value-satisfies-type? value (:items type))))

      (set-type? type)
      (set-value-satisfies-type? value (:members type))

      (var-type? type)
      (and (var? value)
           (value-satisfies-type? @value (:inner type)))

      :else false)))

(declare check-cast)

(defn map-cast-children
  [source-type target-type opts]
  (let [source-entries (:entries (schema->type source-type))
        target-entries (:entries (schema->type target-type))
        required-missing (atom (->> target-entries keys
                                    (filter required-map-key-type?)
                                    set))
        children (reduce (fn [acc [actual-k actual-v]]
                           (if-let [{:keys [key value kind]} (matching-map-entry target-entries actual-k)]
                             (let [_ (swap! required-missing disj key)
                                   value-result (with-map-path
                                                  (check-cast actual-v value opts)
                                                  key)
                                   nullable-result (when (and (optional-key-type? actual-k)
                                                               (= kind :required-explicit))
                                                     (with-map-path
                                                       (cast-fail source-type
                                                                  target-type
                                                                  :map-nullable-key
                                                                  (:polarity opts)
                                                                  :nullable-key
                                                                  []
                                                                  {:actual-key actual-k
                                                                   :expected-key key})
                                                       key))]
                               (cond-> acc
                                 true (conj value-result)
                                 nullable-result (conj nullable-result)))
                             (conj acc
                                   (with-map-path
                                     (cast-fail source-type
                                                target-type
                                                :map-unexpected-key
                                                (:polarity opts)
                                                :unexpected-key
                                                []
                                                {:actual-key actual-k})
                                     actual-k))))
                         []
                         source-entries)]
    (into children
          (map (fn [missing-k]
                 (with-map-path
                   (cast-fail source-type
                              target-type
                              :map-missing-key
                              (:polarity opts)
                              :missing-key
                              []
                              {:expected-key missing-k})
                   missing-k)))
          @required-missing)))

(defn collection-cast-children
  [segment-kind source-items target-items opts]
  (mapv (fn [idx source-item target-item]
          (with-cast-path (check-cast source-item target-item opts)
            {:kind segment-kind
             :index idx}))
        (range)
        source-items
        target-items))

(defn expand-vector-items
  [type slot-count]
  (let [items (:items type)]
    (if (:homogeneous? type)
      (vec (repeat slot-count (or (first items) Dyn)))
      items)))

(defn vector-cast-slot-count
  [source-type target-type]
  (let [source-count (count (:items source-type))
        target-count (count (:items target-type))
        source-homogeneous? (:homogeneous? source-type)
        target-homogeneous? (:homogeneous? target-type)]
    (cond
      (and source-homogeneous? target-homogeneous?) 1
      target-homogeneous? source-count
      source-homogeneous? target-count
      (= source-count target-count) source-count
      :else nil)))

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
                    (with-cast-path
                      (cast-fail source-member
                                 (or (first target-members) Dyn)
                                 :set-element
                                 (:polarity opts)
                                 :element-mismatch)
                      {:kind :set-member
                       :member source-member}))))
          []
          source-members))

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

       (forall-type? target-type)
       (if (contains? (type-free-vars source-type) (:binder target-type))
         (cast-fail source-type
                    target-type
                    :generalize
                    polarity
                    :forall-capture
                    []
                    {:binder (:binder target-type)
                     :cast-state (cast-state opts)})
         (let [child (check-cast source-type
                                 (:body target-type)
                                 (with-abstract-var opts (:binder target-type)))]
           (if (:ok? child)
             (cast-ok source-type
                      target-type
                      :generalize
                      [child]
                      {:binder (:binder target-type)
                       :cast-state (cast-state opts)})
             (cast-fail source-type
                        target-type
                        :generalize
                        polarity
                        :generalize-failed
                        [child]
                        {:binder (:binder target-type)
                         :cast-state (cast-state opts)}))))

       (forall-type? source-type)
       (let [instantiated (type-substitute (:body source-type)
                                           (:binder source-type)
                                           Dyn)
             child (check-cast instantiated target-type
                               (with-nu-binding opts (:binder source-type) Dyn))]
         (if (:ok? child)
           (cast-ok source-type
                    target-type
                    :instantiate
                    [child]
                    {:binder (:binder source-type)
                     :instantiated-type instantiated
                     :cast-state (cast-state opts)})
           (cast-fail source-type
                      target-type
                      :instantiate
                      polarity
                      :instantiate-failed
                      [child]
                      {:binder (:binder source-type)
                       :instantiated-type instantiated
                       :cast-state (cast-state opts)})))

       (and (type-var-type? source-type)
            (dyn-type? target-type))
       (let [sealed-type (->SealedDynT source-type)]
         (cast-ok source-type
                  target-type
                  :seal
                  []
                  {:sealed-type sealed-type
                   :cast-state (:cast-state (register-seal opts sealed-type))}))

       (dyn-type? target-type)
       (cast-ok source-type target-type :target-dyn)

       (union-type? target-type)
       (let [children (indexed-cast-children :target-union-branch
                                             #(check-cast source-type % opts)
                                             (:members target-type))]
         (if-let [success (some #(when (:ok? %) %) children)]
           (cast-ok source-type target-type :target-union children {:chosen-rule (:rule success)})
           (cast-fail source-type target-type :target-union polarity :no-union-branch children)))

       (union-type? source-type)
       (let [children (indexed-cast-children :source-union-branch
                                             #(check-cast % target-type opts)
                                             (:members source-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :source-union children)
           (cast-fail source-type target-type :source-union polarity :source-branch-failed children)))

       (intersection-type? target-type)
       (let [children (indexed-cast-children :target-intersection-branch
                                             #(check-cast source-type % opts)
                                             (:members target-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :target-intersection children)
           (cast-fail source-type target-type :target-intersection polarity :target-component-failed children)))

       (intersection-type? source-type)
       (let [children (indexed-cast-children :source-intersection-branch
                                             #(check-cast % target-type opts)
                                             (:members source-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :source-intersection children)
           (cast-fail source-type target-type :source-intersection polarity :source-component-failed children)))

       (value-type? source-type)
       (if (value-satisfies-type? (:value source-type) target-type)
           (cast-ok source-type target-type :value-exact)
           (cast-fail source-type target-type :value-exact polarity :exact-value-mismatch))

       (value-type? target-type)
       (if (value-satisfies-type? (:value target-type) source-type)
           (cast-ok source-type target-type :target-value)
           (cast-fail source-type target-type :target-value polarity :target-value-mismatch))

       (and (maybe-type? source-type) (maybe-type? target-type))
       (let [child (with-cast-path (check-cast (:inner source-type) (:inner target-type) opts)
                     {:kind :maybe-value})]
         (if (:ok? child)
           (cast-ok source-type target-type :maybe-both [child])
           (cast-fail source-type target-type :maybe-both polarity :maybe-inner-failed [child])))

       (maybe-type? target-type)
       (let [child (with-cast-path (check-cast source-type (:inner target-type) opts)
                     {:kind :maybe-value})]
         (if (:ok? child)
           (cast-ok source-type target-type :maybe-target [child])
           (cast-fail source-type target-type :maybe-target polarity :maybe-target-inner-failed [child])))

       (maybe-type? source-type)
       (cast-fail source-type target-type :maybe-source polarity :nullable-source)

       (optional-key-type? source-type)
       (check-cast (:inner source-type) target-type opts)

       (optional-key-type? target-type)
       (check-cast source-type (:inner target-type) opts)

       (var-type? source-type)
       (check-cast (:inner source-type) target-type opts)

       (var-type? target-type)
       (check-cast source-type (:inner target-type) opts)

       (type-var-type? target-type)
       (cond
         (sealed-dyn-type? source-type)
         (if (= (sealed-ground-name source-type) (type-var-name target-type))
           (cast-ok source-type
                    target-type
                    :sealed-collapse
                    []
                    {:cast-state (cast-state opts)})
           (cast-fail source-type
                      target-type
                      :sealed-collapse
                      polarity
                      :sealed-ground-mismatch
                      []
                      {:cast-state (cast-state opts)}))

         (or (dyn-type? source-type)
             (placeholder-type? source-type))
         (cast-ok source-type
                  target-type
                  :type-var-target
                  []
                  {:cast-state (cast-state opts)})

         :else
         (cast-fail source-type
                    target-type
                    :type-var-target
                    polarity
                    :abstract-target-mismatch
                    []
                    {:cast-state (cast-state opts)}))

       (type-var-type? source-type)
       (cast-fail source-type
                  target-type
                  :type-var-source
                  polarity
                  :abstract-source-mismatch
                  []
                  {:cast-state (cast-state opts)})

       (sealed-dyn-type? source-type)
       (cast-fail source-type
                  target-type
                  :sealed-conflict
                  polarity
                  :sealed-mismatch
                  []
                  {:cast-state (cast-state opts)})

       (and (fun-type? source-type) (fun-type? target-type))
       (let [children (mapv (fn [target-method]
                              (if-let [source-method (matching-source-method source-type target-method)]
                                (let [domain-results (mapv (fn [idx target-input source-input]
                                                             (with-cast-path
                                                               (check-cast target-input
                                                                           source-input
                                                                           (update opts :polarity flip-polarity))
                                                               {:kind :function-domain
                                                                :index idx}))
                                                           (range)
                                                           (:inputs target-method)
                                                           (:inputs source-method))
                                      range-result (with-cast-path
                                                     (check-cast (:output source-method)
                                                                 (:output target-method)
                                                                 opts)
                                                     {:kind :function-range})
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
       (if-let [slot-count (vector-cast-slot-count source-type target-type)]
         (let [source-items (expand-vector-items source-type slot-count)
               target-items (expand-vector-items target-type slot-count)
               children (collection-cast-children :vector-index source-items target-items opts)]
           (if (all-ok? children)
             (cast-ok source-type target-type :vector children)
             (cast-fail source-type target-type :vector polarity :vector-element-failed children)))
         (cast-fail source-type target-type :vector polarity :vector-arity-mismatch))

       (and (seq-type? source-type) (seq-type? target-type))
       (let [source-items (:items source-type)
             target-items (:items target-type)]
         (if (= (count source-items) (count target-items))
           (let [children (collection-cast-children :seq-index source-items target-items opts)]
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

       (or (ground-type? source-type)
           (refinement-type? source-type)
           (adapter-leaf-type? source-type))
       (if (leaf-overlap? source-type target-type)
         (cast-ok source-type target-type :leaf-overlap)
         (cast-fail source-type target-type :leaf-overlap polarity :leaf-mismatch))

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
  (some-> (matching-map-entry m k) :value))

(defn valued-get
  [m k]
  (get-by-matching-schema m k))

(declare matches-map)

(defn matches-map
  [expected actual-k actual-v]
  (let [expected (canonicalize-schema expected)
        actual-v (canonicalize-schema actual-v)
        matched-entry (matching-map-entry expected actual-k)]
    (when matched-entry
      (nested-value-compatible? (:value matched-entry) actual-v))))

(defn required-key?
  [k]
  (= :required-explicit (map-entry-kind k)))

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
