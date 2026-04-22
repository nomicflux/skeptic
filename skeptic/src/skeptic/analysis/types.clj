(ns skeptic.analysis.types)

(def semantic-type-tag-key
  :skeptic.analysis.types/semantic-type)

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
  :skeptic.analysis.types/dyn-type)

(def bottom-type-tag
  :skeptic.analysis.types/bottom-type)

(def ground-type-tag
  :skeptic.analysis.types/ground-type)

(def numeric-dyn-type-tag
  :skeptic.analysis.types/numeric-dyn-type)

(def refinement-type-tag
  :skeptic.analysis.types/refinement-type)

(def adapter-leaf-type-tag
  :skeptic.analysis.types/adapter-leaf-type)

(def optional-key-type-tag
  :skeptic.analysis.types/optional-key-type)

(def fn-method-type-tag
  :skeptic.analysis.types/fn-method-type)

(def fun-type-tag
  :skeptic.analysis.types/fun-type)

(def maybe-type-tag
  :skeptic.analysis.types/maybe-type)

(def union-type-tag
  :skeptic.analysis.types/union-type)

(def intersection-type-tag
  :skeptic.analysis.types/intersection-type)

(def map-type-tag
  :skeptic.analysis.types/map-type)

(def vector-type-tag
  :skeptic.analysis.types/vector-type)

(def set-type-tag
  :skeptic.analysis.types/set-type)

(def seq-type-tag
  :skeptic.analysis.types/seq-type)

(def var-type-tag
  :skeptic.analysis.types/var-type)

(def placeholder-type-tag
  :skeptic.analysis.types/placeholder-type)

(def inf-cycle-type-tag
  :skeptic.analysis.types/inf-cycle-type)

(def value-type-tag
  :skeptic.analysis.types/value-type)

(def type-var-type-tag
  :skeptic.analysis.types/type-var-type)

(def forall-type-tag
  :skeptic.analysis.types/forall-type)

(def sealed-dyn-type-tag
  :skeptic.analysis.types/sealed-dyn-type)

(def conditional-type-tag
  :skeptic.analysis.types/conditional-type)

(def known-semantic-type-tags
  #{dyn-type-tag
    bottom-type-tag
    ground-type-tag
    numeric-dyn-type-tag
    refinement-type-tag
    adapter-leaf-type-tag
    optional-key-type-tag
    fn-method-type-tag
    fun-type-tag
    maybe-type-tag
    union-type-tag
    intersection-type-tag
    map-type-tag
    vector-type-tag
    set-type-tag
    seq-type-tag
    var-type-tag
    placeholder-type-tag
    inf-cycle-type-tag
    value-type-tag
    type-var-type-tag
    forall-type-tag
    sealed-dyn-type-tag
    conditional-type-tag})

(defprotocol SemanticType
  (semantic-tag [this]))

(defrecord DynT [prov]
  SemanticType (semantic-tag [_] dyn-type-tag))

(defrecord BottomT [prov]
  SemanticType (semantic-tag [_] bottom-type-tag))

(defrecord GroundT [prov ground display-form]
  SemanticType (semantic-tag [_] ground-type-tag))

(defrecord NumericDynT [prov]
  SemanticType (semantic-tag [_] numeric-dyn-type-tag))

(defrecord RefinementT [prov base display-form accepts? adapter-data]
  SemanticType (semantic-tag [_] refinement-type-tag))

(defrecord AdapterLeafT [prov adapter display-form accepts? adapter-data]
  SemanticType (semantic-tag [_] adapter-leaf-type-tag))

(defrecord OptionalKeyT [prov inner]
  SemanticType (semantic-tag [_] optional-key-type-tag))

(defrecord FnMethodT [prov inputs output min-arity variadic? names]
  SemanticType (semantic-tag [_] fn-method-type-tag))

(defrecord FunT [prov methods]
  SemanticType (semantic-tag [_] fun-type-tag))

(defrecord MaybeT [prov inner]
  SemanticType (semantic-tag [_] maybe-type-tag))

(defrecord UnionT [prov members]
  SemanticType (semantic-tag [_] union-type-tag))

(defrecord IntersectionT [prov members]
  SemanticType (semantic-tag [_] intersection-type-tag))

(defrecord MapT [prov entries]
  SemanticType (semantic-tag [_] map-type-tag))

(defrecord VectorT [prov items homogeneous?]
  SemanticType (semantic-tag [_] vector-type-tag))

(defrecord SetT [prov members homogeneous?]
  SemanticType (semantic-tag [_] set-type-tag))

(defrecord SeqT [prov items homogeneous?]
  SemanticType (semantic-tag [_] seq-type-tag))

(defrecord VarT [prov inner]
  SemanticType (semantic-tag [_] var-type-tag))

(defrecord PlaceholderT [prov ref]
  SemanticType (semantic-tag [_] placeholder-type-tag))

(defrecord InfCycleT [prov ref]
  SemanticType (semantic-tag [_] inf-cycle-type-tag))

(defrecord ValueT [prov inner value]
  SemanticType (semantic-tag [_] value-type-tag))

(defrecord TypeVarT [prov name]
  SemanticType (semantic-tag [_] type-var-type-tag))

(defrecord ForallT [prov binder body]
  SemanticType (semantic-tag [_] forall-type-tag))

(defrecord SealedDynT [prov ground]
  SemanticType (semantic-tag [_] sealed-dyn-type-tag))

(defrecord ConditionalT [prov branches]
  SemanticType (semantic-tag [_] conditional-type-tag))

(defn Dyn
  [prov]
  (->DynT prov))

(defn BottomType
  [prov]
  (->BottomT prov))

(defn NumericDyn
  [prov]
  (->NumericDynT prov))

(defn semantic-type-value?
  [value]
  (satisfies? SemanticType value))

(defn semantic-type-tag
  [value]
  (when (semantic-type-value? value)
    (semantic-tag value)))

(defn known-semantic-type-tag?
  [tag]
  (contains? known-semantic-type-tags tag))

(defn dyn-type? [t] (instance? DynT t))
(defn bottom-type? [t] (instance? BottomT t))
(defn ground-type? [t] (instance? GroundT t))
(defn numeric-dyn-type? [t] (instance? NumericDynT t))
(defn refinement-type? [t] (instance? RefinementT t))
(defn adapter-leaf-type? [t] (instance? AdapterLeafT t))
(defn optional-key-type? [t] (instance? OptionalKeyT t))
(defn fn-method-type? [t] (instance? FnMethodT t))
(defn fun-type? [t] (instance? FunT t))
(defn maybe-type? [t] (instance? MaybeT t))
(defn union-type? [t] (instance? UnionT t))
(defn intersection-type? [t] (instance? IntersectionT t))
(defn map-type? [t] (instance? MapT t))
(defn vector-type? [t] (instance? VectorT t))
(defn set-type? [t] (instance? SetT t))
(defn seq-type? [t] (instance? SeqT t))
(defn var-type? [t] (instance? VarT t))
(defn type-var-type? [t] (instance? TypeVarT t))
(defn forall-type? [t] (instance? ForallT t))
(defn sealed-dyn-type? [t] (instance? SealedDynT t))
(defn conditional-type? [t] (instance? ConditionalT t))
(defn placeholder-type? [t] (instance? PlaceholderT t))
(defn inf-cycle-type? [t] (instance? InfCycleT t))
(defn value-type? [t] (instance? ValueT t))

(defn fun-methods
  [fun-t]
  (:methods fun-t))

(defn fn-method-inputs
  [method]
  (:inputs method))

(defn fn-method-output
  [method]
  (:output method))

(defn fn-method-input-names
  [method]
  (:names method))

(defn select-method
  [methods arity]
  (or (some #(when (= (:min-arity %) arity) %) methods)
      (some #(when (:variadic? %) %) methods)
      (when-let [eligible (seq (filter #(<= (:min-arity %) arity) methods))]
        (apply max-key :min-arity eligible))))

(defn- strip-runtime-closures
  [t]
  (cond
    (not (semantic-type-value? t)) t

    (numeric-dyn-type? t)
    t

    (refinement-type? t)
    (-> t (assoc :accepts? nil) (update :base strip-runtime-closures))

    (adapter-leaf-type? t)
    (assoc t :accepts? nil)

    (inf-cycle-type? t)
    t

    (optional-key-type? t) (update t :inner strip-runtime-closures)
    (maybe-type? t) (update t :inner strip-runtime-closures)
    (var-type? t) (update t :inner strip-runtime-closures)
    (value-type? t) (update t :inner strip-runtime-closures)
    (forall-type? t) (update t :body strip-runtime-closures)
    (sealed-dyn-type? t) (update t :ground strip-runtime-closures)

    (conditional-type? t)
    (update t :branches (fn [bs] (mapv (fn [[_ typ]] [nil (strip-runtime-closures typ)]) bs)))

    (union-type? t) (update t :members #(mapv strip-runtime-closures %))
    (intersection-type? t) (update t :members #(mapv strip-runtime-closures %))
    (vector-type? t) (update t :items #(mapv strip-runtime-closures %))
    (seq-type? t) (update t :items #(mapv strip-runtime-closures %))
    (set-type? t) (update t :members #(into #{} (map strip-runtime-closures) %))

    (fn-method-type? t)
    (-> t
        (update :inputs #(mapv strip-runtime-closures %))
        (update :output strip-runtime-closures))

    (fun-type? t)
    (update t :methods #(mapv strip-runtime-closures %))

    (map-type? t)
    (update t :entries
            #(into {} (map (fn [[k v]] [(strip-runtime-closures k)
                                        (strip-runtime-closures v)])) %))

    :else t))

(declare type=?)

(defn- strip-prov
  [x]
  (cond
    (semantic-type-value? x)
    (reduce-kv (fn [acc k v]
                 (assoc acc k (if (= k :prov) nil (strip-prov v))))
               x
               x)
    (map? x) (into {} (map (fn [[k v]] [(strip-prov k) (strip-prov v)])) x)
    (vector? x) (mapv strip-prov x)
    (set? x) (into #{} (map strip-prov) x)
    (seq? x) (doall (map strip-prov x))
    :else x))

(defn type=?
  [a b]
  (= (strip-prov a) (strip-prov b)))

(defn dedup-types
  [types]
  (into #{} (vals (reduce (fn [acc t] (assoc acc (strip-prov t) t)) {} types))))

(defn type-equal?
  [a b]
  (type=? (strip-runtime-closures a) (strip-runtime-closures b)))

(defn ref-display-form
  [ref]
  (cond
    (symbol? ref) ref
    (and (vector? ref)
         (seq (filter symbol? ref)))
    (last (filter symbol? ref))
    (keyword? ref) (symbol (name ref))
    (string? ref) (symbol ref)
    :else 'Unknown))

(defn placeholder-display-form
  [ref]
  (ref-display-form ref))
