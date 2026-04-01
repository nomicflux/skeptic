(ns skeptic.analysis.types)

(def semantic-type-tag-key
  :skeptic.analysis.schema/semantic-type)

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

(def dyn-type-tag
  :skeptic.analysis.schema/dyn-type)

(def bottom-type-tag
  :skeptic.analysis.schema/bottom-type)

(def ground-type-tag
  :skeptic.analysis.schema/ground-type)

(def refinement-type-tag
  :skeptic.analysis.schema/refinement-type)

(def adapter-leaf-type-tag
  :skeptic.analysis.schema/adapter-leaf-type)

(def optional-key-type-tag
  :skeptic.analysis.schema/optional-key-type)

(def fn-method-type-tag
  :skeptic.analysis.schema/fn-method-type)

(def fun-type-tag
  :skeptic.analysis.schema/fun-type)

(def maybe-type-tag
  :skeptic.analysis.schema/maybe-type)

(def union-type-tag
  :skeptic.analysis.schema/union-type)

(def intersection-type-tag
  :skeptic.analysis.schema/intersection-type)

(def map-type-tag
  :skeptic.analysis.schema/map-type)

(def vector-type-tag
  :skeptic.analysis.schema/vector-type)

(def set-type-tag
  :skeptic.analysis.schema/set-type)

(def seq-type-tag
  :skeptic.analysis.schema/seq-type)

(def var-type-tag
  :skeptic.analysis.schema/var-type)

(def placeholder-type-tag
  :skeptic.analysis.schema/placeholder-type)

(def value-type-tag
  :skeptic.analysis.schema/value-type)

(def type-var-type-tag
  :skeptic.analysis.schema/type-var-type)

(def forall-type-tag
  :skeptic.analysis.schema/forall-type)

(def sealed-dyn-type-tag
  :skeptic.analysis.schema/sealed-dyn-type)

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

(defn placeholder-display-form
  [ref]
  (cond
    (symbol? ref) ref
    (and (vector? ref)
         (seq (filter symbol? ref)))
    (last (filter symbol? ref))
    (keyword? ref) (symbol (name ref))
    (string? ref) (symbol ref)
    :else 'Unknown))
