(ns skeptic.analysis.types
  (:require [skeptic.provenance :as prov]))

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

(defn ->DynT
  [prov]
  (prov/attach {semantic-type-tag-key dyn-type-tag} prov))

(defn ->BottomT
  [prov]
  (prov/attach {semantic-type-tag-key bottom-type-tag} prov))

(defn ->GroundT
  [prov ground display-form]
  (prov/attach {semantic-type-tag-key ground-type-tag
                :ground ground
                :display-form display-form}
               prov))

(defn ->NumericDynT
  [prov]
  (prov/attach {semantic-type-tag-key numeric-dyn-type-tag} prov))

(defn ->RefinementT
  [prov base display-form accepts? adapter-data]
  (prov/attach {semantic-type-tag-key refinement-type-tag
                :base base
                :display-form display-form
                :accepts? accepts?
                :adapter-data adapter-data}
               prov))

(defn ->AdapterLeafT
  [prov adapter display-form accepts? adapter-data]
  (prov/attach {semantic-type-tag-key adapter-leaf-type-tag
                :adapter adapter
                :display-form display-form
                :accepts? accepts?
                :adapter-data adapter-data}
               prov))

(defn ->OptionalKeyT
  [prov inner]
  (prov/attach {semantic-type-tag-key optional-key-type-tag
                :inner inner}
               prov))

(defn ->FnMethodT
  [prov inputs output min-arity variadic? names]
  (prov/attach {semantic-type-tag-key fn-method-type-tag
                :inputs inputs
                :output output
                :min-arity min-arity
                :variadic? variadic?
                :names names}
               prov))

(defn ->FunT
  [prov methods]
  (prov/attach {semantic-type-tag-key fun-type-tag
                :methods methods}
               prov))

(defn ->MaybeT
  [prov inner]
  (prov/attach {semantic-type-tag-key maybe-type-tag
                :inner inner}
               prov))

(defn ->UnionT
  [prov members]
  (prov/attach {semantic-type-tag-key union-type-tag
                :members members}
               prov))

(defn ->IntersectionT
  [prov members]
  (prov/attach {semantic-type-tag-key intersection-type-tag
                :members members}
               prov))

(defn ->MapT
  [prov entries]
  (prov/attach {semantic-type-tag-key map-type-tag
                :entries entries}
               prov))

(defn ->VectorT
  [prov items homogeneous?]
  (prov/attach {semantic-type-tag-key vector-type-tag
                :items items
                :homogeneous? homogeneous?}
               prov))

(defn ->SetT
  [prov members homogeneous?]
  (prov/attach {semantic-type-tag-key set-type-tag
                :members members
                :homogeneous? homogeneous?}
               prov))

(defn ->SeqT
  [prov items homogeneous?]
  (prov/attach {semantic-type-tag-key seq-type-tag
                :items items
                :homogeneous? homogeneous?}
               prov))

(defn ->VarT
  [prov inner]
  (prov/attach {semantic-type-tag-key var-type-tag
                :inner inner}
               prov))

(defn ->PlaceholderT
  [prov ref]
  (prov/attach {semantic-type-tag-key placeholder-type-tag
                :ref ref}
               prov))

(defn ->InfCycleT
  ([prov]
   (->InfCycleT prov nil))
  ([prov ref]
   (prov/attach (cond-> {semantic-type-tag-key inf-cycle-type-tag}
                  (some? ref) (assoc :ref ref))
                prov)))

(defn ->ValueT
  [prov inner value]
  (prov/attach {semantic-type-tag-key value-type-tag
                :inner inner
                :value value}
               prov))

(defn ->TypeVarT
  [prov name]
  (prov/attach {semantic-type-tag-key type-var-type-tag
                :name name}
               prov))

(defn ->ForallT
  [prov binder body]
  (prov/attach {semantic-type-tag-key forall-type-tag
                :binder binder
                :body body}
               prov))

(defn ->SealedDynT
  [prov ground]
  (prov/attach {semantic-type-tag-key sealed-dyn-type-tag
                :ground ground}
               prov))

(defn ->ConditionalT
  [prov branches]
  (prov/attach {semantic-type-tag-key conditional-type-tag
                :branches branches}
               prov))

(defn Dyn
  [prov]
  (->DynT prov))

(defn BottomType
  [prov]
  (->BottomT prov))

(defn NumericDyn
  [prov]
  (->NumericDynT prov))

(defn semantic-type-tag
  [value]
  (when (map? value)
    (get value semantic-type-tag-key)))

(defn known-semantic-type-tag?
  [tag]
  (contains? known-semantic-type-tags tag))

(defn dyn-type?
  [t]
  (tagged-map? t semantic-type-tag-key dyn-type-tag))

(defn bottom-type?
  [t]
  (tagged-map? t semantic-type-tag-key bottom-type-tag))

(defn ground-type?
  [t]
  (tagged-map? t semantic-type-tag-key ground-type-tag))

(defn numeric-dyn-type?
  [t]
  (tagged-map? t semantic-type-tag-key numeric-dyn-type-tag))

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
  (tagged-map? t semantic-type-tag-key fn-method-type-tag))

(defn fun-type?
  [t]
  (tagged-map? t semantic-type-tag-key fun-type-tag))

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

(defn maybe-type?
  [t]
  (tagged-map? t semantic-type-tag-key maybe-type-tag))

(defn union-type?
  [t]
  (tagged-map? t semantic-type-tag-key union-type-tag))

(defn intersection-type?
  [t]
  (tagged-map? t semantic-type-tag-key intersection-type-tag))

(defn map-type?
  [t]
  (tagged-map? t semantic-type-tag-key map-type-tag))

(defn vector-type?
  [t]
  (tagged-map? t semantic-type-tag-key vector-type-tag))

(defn set-type?
  [t]
  (tagged-map? t semantic-type-tag-key set-type-tag))

(defn seq-type?
  [t]
  (tagged-map? t semantic-type-tag-key seq-type-tag))

(defn var-type?
  [t]
  (tagged-map? t semantic-type-tag-key var-type-tag))

(defn type-var-type?
  [t]
  (tagged-map? t semantic-type-tag-key type-var-type-tag))

(defn forall-type?
  [t]
  (tagged-map? t semantic-type-tag-key forall-type-tag))

(defn sealed-dyn-type?
  [t]
  (tagged-map? t semantic-type-tag-key sealed-dyn-type-tag))

(defn conditional-type?
  [t]
  (tagged-map? t semantic-type-tag-key conditional-type-tag))

(defn placeholder-type?
  [t]
  (tagged-map? t semantic-type-tag-key placeholder-type-tag))

(defn inf-cycle-type?
  [t]
  (tagged-map? t semantic-type-tag-key inf-cycle-type-tag))

(defn value-type?
  [t]
  (tagged-map? t semantic-type-tag-key value-type-tag))

(defn semantic-type-value?
  [value]
  (known-semantic-type-tag? (semantic-type-tag value)))

(defn- strip-runtime-closures
  [t]
  (cond
    (not (semantic-type-value? t)) t

    (numeric-dyn-type? t)
    t

    (refinement-type? t)
    (-> t (dissoc :accepts?) (update :base strip-runtime-closures))

    (adapter-leaf-type? t)
    (dissoc t :accepts?)

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

(defn type-equal?
  [a b]
  (= (strip-runtime-closures a) (strip-runtime-closures b)))

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
