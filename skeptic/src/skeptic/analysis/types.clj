(ns skeptic.analysis.types
  (:require [schema.core :as s]
            [skeptic.analysis.types.proto :as proto]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance.schema :as provs]))

(def semantic-type-tag-key
  :skeptic.analysis.types/semantic-type)

(defn tagged-map?
  [value tag-key tag]
  (and (map? value)
       (= tag (get value tag-key))))

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

(defrecord DynTRec [prov]
  proto/SemanticType (semantic-tag [_] dyn-type-tag))

(defrecord BottomTRec [prov]
  proto/SemanticType (semantic-tag [_] bottom-type-tag))

(defrecord GroundTRec [prov ground display-form]
  proto/SemanticType (semantic-tag [_] ground-type-tag))

(defrecord NumericDynTRec [prov]
  proto/SemanticType (semantic-tag [_] numeric-dyn-type-tag))

(defrecord RefinementTRec [prov base display-form accepts? adapter-data]
  proto/SemanticType (semantic-tag [_] refinement-type-tag))

(defrecord AdapterLeafTRec [prov adapter display-form accepts? adapter-data]
  proto/SemanticType (semantic-tag [_] adapter-leaf-type-tag))

(defrecord OptionalKeyTRec [prov inner]
  proto/SemanticType (semantic-tag [_] optional-key-type-tag))

(defrecord FnMethodTRec [prov inputs output min-arity variadic? names]
  proto/SemanticType (semantic-tag [_] fn-method-type-tag))

(defrecord FunTRec [prov methods]
  proto/SemanticType (semantic-tag [_] fun-type-tag))

(defrecord MaybeTRec [prov inner]
  proto/SemanticType (semantic-tag [_] maybe-type-tag))

(defrecord UnionTRec [prov members]
  proto/SemanticType (semantic-tag [_] union-type-tag))

(defrecord IntersectionTRec [prov members]
  proto/SemanticType (semantic-tag [_] intersection-type-tag))

(defrecord MapTRec [prov entries]
  proto/SemanticType (semantic-tag [_] map-type-tag))

(defrecord VectorTRec [prov items homogeneous?]
  proto/SemanticType (semantic-tag [_] vector-type-tag))

(defrecord SetTRec [prov members homogeneous?]
  proto/SemanticType (semantic-tag [_] set-type-tag))

(defrecord SeqTRec [prov items homogeneous?]
  proto/SemanticType (semantic-tag [_] seq-type-tag))

(defrecord VarTRec [prov inner]
  proto/SemanticType (semantic-tag [_] var-type-tag))

(defrecord PlaceholderTRec [prov ref]
  proto/SemanticType (semantic-tag [_] placeholder-type-tag))

(defrecord InfCycleTRec [prov ref]
  proto/SemanticType (semantic-tag [_] inf-cycle-type-tag))

(defrecord ValueTRec [prov inner value]
  proto/SemanticType (semantic-tag [_] value-type-tag))

(defrecord TypeVarTRec [prov name]
  proto/SemanticType (semantic-tag [_] type-var-type-tag))

(defrecord ForallTRec [prov binder body]
  proto/SemanticType (semantic-tag [_] forall-type-tag))

(defrecord SealedDynTRec [prov ground]
  proto/SemanticType (semantic-tag [_] sealed-dyn-type-tag))

(defrecord ConditionalTRec [prov branches pred-forms]
  proto/SemanticType (semantic-tag [_] conditional-type-tag))

(defn- ensure-prov!
  [record-name prov]
  (when (nil? prov)
    (throw (IllegalArgumentException.
            (format "%s requires non-nil provenance" record-name)))))

(s/defn ->DynT :- ats/SemanticType [prov :- provs/Provenance] (ensure-prov! "DynT" prov) (DynTRec. prov))
(s/defn ->BottomT :- ats/SemanticType [prov :- provs/Provenance] (ensure-prov! "BottomT" prov) (BottomTRec. prov))
(s/defn ->GroundT :- ats/SemanticType [prov :- provs/Provenance ground display-form] (ensure-prov! "GroundT" prov) (GroundTRec. prov ground display-form))
(s/defn ->NumericDynT :- ats/SemanticType [prov :- provs/Provenance] (ensure-prov! "NumericDynT" prov) (NumericDynTRec. prov))
(s/defn ->RefinementT :- ats/SemanticType [prov :- provs/Provenance base display-form accepts? adapter-data] (ensure-prov! "RefinementT" prov) (RefinementTRec. prov base display-form accepts? adapter-data))
(s/defn ->AdapterLeafT :- ats/SemanticType [prov :- provs/Provenance adapter display-form accepts? adapter-data] (ensure-prov! "AdapterLeafT" prov) (AdapterLeafTRec. prov adapter display-form accepts? adapter-data))
(s/defn ->OptionalKeyT :- ats/SemanticType [prov :- provs/Provenance inner] (ensure-prov! "OptionalKeyT" prov) (OptionalKeyTRec. prov inner))
(s/defn ->FnMethodT :- ats/SemanticType [prov :- provs/Provenance inputs output min-arity variadic? names] (ensure-prov! "FnMethodT" prov) (FnMethodTRec. prov inputs output min-arity variadic? names))
(s/defn ->FunT :- ats/SemanticType [prov :- provs/Provenance methods] (ensure-prov! "FunT" prov) (FunTRec. prov methods))
(s/defn ->MaybeT :- ats/SemanticType [prov :- provs/Provenance inner] (ensure-prov! "MaybeT" prov) (MaybeTRec. prov inner))
(s/defn ->UnionT :- ats/SemanticType [prov :- provs/Provenance members] (ensure-prov! "UnionT" prov) (UnionTRec. prov members))
(s/defn ->IntersectionT :- ats/SemanticType [prov :- provs/Provenance members] (ensure-prov! "IntersectionT" prov) (IntersectionTRec. prov members))
(s/defn ->MapT :- ats/SemanticType [prov :- provs/Provenance entries] (ensure-prov! "MapT" prov) (MapTRec. prov entries))
(s/defn ->VectorT :- ats/SemanticType [prov :- provs/Provenance items homogeneous?] (ensure-prov! "VectorT" prov) (VectorTRec. prov items homogeneous?))
(s/defn ->SetT :- ats/SemanticType [prov :- provs/Provenance members homogeneous?] (ensure-prov! "SetT" prov) (SetTRec. prov members homogeneous?))
(s/defn ->SeqT :- ats/SemanticType [prov :- provs/Provenance items homogeneous?] (ensure-prov! "SeqT" prov) (SeqTRec. prov items homogeneous?))
(s/defn ->VarT :- ats/SemanticType [prov :- provs/Provenance inner] (ensure-prov! "VarT" prov) (VarTRec. prov inner))
(s/defn ->PlaceholderT :- ats/SemanticType [prov :- provs/Provenance ref] (ensure-prov! "PlaceholderT" prov) (PlaceholderTRec. prov ref))
(s/defn ->InfCycleT :- ats/SemanticType [prov :- provs/Provenance ref] (ensure-prov! "InfCycleT" prov) (InfCycleTRec. prov ref))
(s/defn ->ValueT :- ats/SemanticType [prov :- provs/Provenance inner value] (ensure-prov! "ValueT" prov) (ValueTRec. prov inner value))
(s/defn ->TypeVarT :- ats/SemanticType [prov :- provs/Provenance name] (ensure-prov! "TypeVarT" prov) (TypeVarTRec. prov name))
(s/defn ->ForallT :- ats/SemanticType [prov :- provs/Provenance binder body] (ensure-prov! "ForallT" prov) (ForallTRec. prov binder body))
(s/defn ->SealedDynT :- ats/SemanticType [prov :- provs/Provenance ground] (ensure-prov! "SealedDynT" prov) (SealedDynTRec. prov ground))
(s/defn ->ConditionalT :- ats/SemanticType
  ([prov :- provs/Provenance branches] (->ConditionalT prov branches nil))
  ([prov :- provs/Provenance branches pred-forms] (ensure-prov! "ConditionalT" prov) (ConditionalTRec. prov branches pred-forms)))

(s/defn Dyn :- ats/SemanticType
  [prov :- provs/Provenance]
  (->DynT prov))

(s/defn BottomType :- ats/SemanticType
  [prov :- provs/Provenance]
  (->BottomT prov))

(s/defn NumericDyn :- ats/SemanticType
  [prov :- provs/Provenance]
  (->NumericDynT prov))

(defn semantic-type-value?
  [value]
  (instance? skeptic.analysis.types.proto.SemanticType value))

(s/defn dyn-type? :- s/Bool [t :- s/Any] (instance? DynTRec t))
(s/defn bottom-type? :- s/Bool [t :- s/Any] (instance? BottomTRec t))
(s/defn ground-type? :- s/Bool [t :- s/Any] (instance? GroundTRec t))
(s/defn numeric-dyn-type? :- s/Bool [t :- s/Any] (instance? NumericDynTRec t))
(s/defn refinement-type? :- s/Bool [t :- s/Any] (instance? RefinementTRec t))
(s/defn adapter-leaf-type? :- s/Bool [t :- s/Any] (instance? AdapterLeafTRec t))
(s/defn optional-key-type? :- s/Bool [t :- s/Any] (instance? OptionalKeyTRec t))
(s/defn fn-method-type? :- s/Bool [t :- s/Any] (instance? FnMethodTRec t))
(s/defn fun-type? :- s/Bool [t :- s/Any] (instance? FunTRec t))
(s/defn maybe-type? :- s/Bool [t :- s/Any] (instance? MaybeTRec t))
(s/defn union-type? :- s/Bool [t :- s/Any] (instance? UnionTRec t))
(s/defn intersection-type? :- s/Bool [t :- s/Any] (instance? IntersectionTRec t))
(s/defn map-type? :- s/Bool [t :- s/Any] (instance? MapTRec t))
(s/defn vector-type? :- s/Bool [t :- s/Any] (instance? VectorTRec t))
(s/defn set-type? :- s/Bool [t :- s/Any] (instance? SetTRec t))
(s/defn seq-type? :- s/Bool [t :- s/Any] (instance? SeqTRec t))
(s/defn var-type? :- s/Bool [t :- s/Any] (instance? VarTRec t))
(s/defn type-var-type? :- s/Bool [t :- s/Any] (instance? TypeVarTRec t))
(s/defn forall-type? :- s/Bool [t :- s/Any] (instance? ForallTRec t))
(s/defn sealed-dyn-type? :- s/Bool [t :- s/Any] (instance? SealedDynTRec t))
(s/defn conditional-type? :- s/Bool [t :- s/Any] (instance? ConditionalTRec t))
(s/defn placeholder-type? :- s/Bool [t :- s/Any] (instance? PlaceholderTRec t))
(s/defn inf-cycle-type? :- s/Bool [t :- s/Any] (instance? InfCycleTRec t))
(s/defn value-type? :- s/Bool [t :- s/Any] (instance? ValueTRec t))

(s/defn fun-methods :- [ats/SemanticType]
  [fun-t :- ats/SemanticType]
  (:methods fun-t))

(s/defn fn-method-inputs :- [ats/SemanticType]
  [method :- ats/SemanticType]
  (:inputs method))

(s/defn fn-method-output :- ats/SemanticType
  [method :- ats/SemanticType]
  (:output method))

(s/defn fn-method-input-names :- (s/maybe [s/Any])
  [method :- ats/SemanticType]
  (:names method))

(s/defn select-method :- (s/maybe ats/SemanticType)
  [methods :- [ats/SemanticType]
   arity :- s/Int]
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
    (update t :branches (fn [bs] (mapv (fn [[_ typ slot3]] [nil (strip-runtime-closures typ) slot3]) bs)))

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

(defn- ordered-type=?
  [same? a b]
  (and (= (count a) (count b))
       (every? true? (map same? a b))))

(defn- take-matching
  [pred xs]
  (loop [prefix []
         xs (seq xs)]
    (when xs
      (let [candidate (first xs)]
        (if (pred candidate)
          [candidate (into prefix (rest xs))]
          (recur (conj prefix candidate) (next xs)))))))

(defn- unordered-type=?
  [same? a b]
  (and (= (count a) (count b))
       (loop [remaining (seq b)
              xs (seq a)]
         (if-not xs
           true
           (when-let [[_ remaining] (take-matching #(same? (first xs) %) remaining)]
             (recur (seq remaining) (next xs)))))))

(defn- map-type=?
  [same? a b]
  (and (= (count a) (count b))
       (loop [remaining (seq b)
              entries (seq a)]
         (if-not entries
           true
           (let [[ak av] (first entries)]
             (when-let [[_ remaining]
                        (take-matching (fn [[bk bv]]
                                         (and (same? ak bk)
                                              (same? av bv)))
                                       remaining)]
               (recur (seq remaining) (next entries))))))))

(defn- branch-type=?
  [same? a b]
  (and (= (first a) (first b))
       (same? (second a) (second b))))

(defn- ordered-branch-type=?
  [same? a b]
  (and (= (count a) (count b))
       (every? true? (map #(branch-type=? same? %1 %2) a b))))

(defn- semantic-type=?
  [same? a b]
  (and (= (proto/semantic-tag a) (proto/semantic-tag b))
       (cond
         (or (dyn-type? a)
             (bottom-type? a)
             (numeric-dyn-type? a))
         true

         (ground-type? a)
         (and (= (:ground a) (:ground b))
              (= (:display-form a) (:display-form b)))

         (refinement-type? a)
         (and (same? (:base a) (:base b))
              (= (:display-form a) (:display-form b))
              (= (:accepts? a) (:accepts? b))
              (same? (:adapter-data a) (:adapter-data b)))

         (adapter-leaf-type? a)
         (and (= (:adapter a) (:adapter b))
              (= (:display-form a) (:display-form b))
              (= (:accepts? a) (:accepts? b))
              (same? (:adapter-data a) (:adapter-data b)))

         (optional-key-type? a)
         (same? (:inner a) (:inner b))

         (fn-method-type? a)
         (and (ordered-type=? same? (:inputs a) (:inputs b))
              (same? (:output a) (:output b))
              (= (:min-arity a) (:min-arity b))
              (= (:variadic? a) (:variadic? b))
              (= (:names a) (:names b)))

         (fun-type? a)
         (ordered-type=? same? (:methods a) (:methods b))

         (maybe-type? a)
         (same? (:inner a) (:inner b))

         (union-type? a)
         (unordered-type=? same? (:members a) (:members b))

         (intersection-type? a)
         (unordered-type=? same? (:members a) (:members b))

         (map-type? a)
         (map-type=? same? (:entries a) (:entries b))

         (vector-type? a)
         (and (ordered-type=? same? (:items a) (:items b))
              (= (:homogeneous? a) (:homogeneous? b)))

         (set-type? a)
         (and (unordered-type=? same? (:members a) (:members b))
              (= (:homogeneous? a) (:homogeneous? b)))

         (seq-type? a)
         (and (ordered-type=? same? (:items a) (:items b))
              (= (:homogeneous? a) (:homogeneous? b)))

         (var-type? a)
         (same? (:inner a) (:inner b))

         (placeholder-type? a)
         (= (:ref a) (:ref b))

         (inf-cycle-type? a)
         (= (:ref a) (:ref b))

         (value-type? a)
         (and (same? (:inner a) (:inner b))
              (= (:value a) (:value b)))

         (type-var-type? a)
         (= (:name a) (:name b))

         (forall-type? a)
         (and (= (:binder a) (:binder b))
              (same? (:body a) (:body b)))

         (sealed-dyn-type? a)
         (same? (:ground a) (:ground b))

         (conditional-type? a)
         (ordered-branch-type=? same? (:branches a) (:branches b))

         :else
         (= a b))))

(s/defn type=? :- s/Any
  [a :- s/Any
   b :- s/Any]
  (letfn [(same? [a b]
            (cond
              (identical? a b) true
              (and (semantic-type-value? a)
                   (semantic-type-value? b)) (semantic-type=? same? a b)
              (or (semantic-type-value? a)
                  (semantic-type-value? b)) false
              (and (map? a) (map? b)) (map-type=? same? a b)
              (and (sequential? a) (sequential? b)) (ordered-type=? same? a b)
              (and (set? a) (set? b)) (unordered-type=? same? a b)
              :else (= a b)))]
    (same? a b)))

(defn- combine-hash
  [seed value]
  (unchecked-add-int (unchecked-multiply-int 31 seed) (int value)))

(defn- ordered-type-hash
  [type-hash-fn xs]
  (reduce combine-hash 1 (map type-hash-fn xs)))

(defn- unordered-type-hash
  [type-hash-fn xs]
  (reduce unchecked-add-int 0 (map type-hash-fn xs)))

(defn- map-type-hash
  [type-hash-fn m]
  (reduce unchecked-add-int
          0
          (map (fn [[k v]]
                 (combine-hash (type-hash-fn k) (type-hash-fn v)))
               m)))

(defn- branch-type-hash
  [type-hash-fn [pred typ]]
  (combine-hash (hash pred) (type-hash-fn typ)))

(defn- type-hash
  [x]
  (letfn [(hash-type [x]
            (cond
              (semantic-type-value? x)
              (let [tag-hash (hash (proto/semantic-tag x))]
                (cond
                  (or (dyn-type? x)
                      (bottom-type? x)
                      (numeric-dyn-type? x))
                  tag-hash

                  (ground-type? x)
                  (-> tag-hash
                      (combine-hash (hash (:ground x)))
                      (combine-hash (hash (:display-form x))))

                  (refinement-type? x)
                  (-> tag-hash
                      (combine-hash (hash-type (:base x)))
                      (combine-hash (hash (:display-form x)))
                      (combine-hash (hash (:accepts? x)))
                      (combine-hash (hash-type (:adapter-data x))))

                  (adapter-leaf-type? x)
                  (-> tag-hash
                      (combine-hash (hash (:adapter x)))
                      (combine-hash (hash (:display-form x)))
                      (combine-hash (hash (:accepts? x)))
                      (combine-hash (hash-type (:adapter-data x))))

                  (optional-key-type? x)
                  (combine-hash tag-hash (hash-type (:inner x)))

                  (fn-method-type? x)
                  (-> tag-hash
                      (combine-hash (ordered-type-hash hash-type (:inputs x)))
                      (combine-hash (hash-type (:output x)))
                      (combine-hash (hash (:min-arity x)))
                      (combine-hash (hash (:variadic? x)))
                      (combine-hash (hash (:names x))))

                  (fun-type? x)
                  (combine-hash tag-hash (ordered-type-hash hash-type (:methods x)))

                  (maybe-type? x)
                  (combine-hash tag-hash (hash-type (:inner x)))

                  (union-type? x)
                  (combine-hash tag-hash (unordered-type-hash hash-type (:members x)))

                  (intersection-type? x)
                  (combine-hash tag-hash (unordered-type-hash hash-type (:members x)))

                  (map-type? x)
                  (combine-hash tag-hash (map-type-hash hash-type (:entries x)))

                  (vector-type? x)
                  (-> tag-hash
                      (combine-hash (ordered-type-hash hash-type (:items x)))
                      (combine-hash (hash (:homogeneous? x))))

                  (set-type? x)
                  (-> tag-hash
                      (combine-hash (unordered-type-hash hash-type (:members x)))
                      (combine-hash (hash (:homogeneous? x))))

                  (seq-type? x)
                  (-> tag-hash
                      (combine-hash (ordered-type-hash hash-type (:items x)))
                      (combine-hash (hash (:homogeneous? x))))

                  (var-type? x)
                  (combine-hash tag-hash (hash-type (:inner x)))

                  (placeholder-type? x)
                  (combine-hash tag-hash (hash (:ref x)))

                  (inf-cycle-type? x)
                  (combine-hash tag-hash (hash (:ref x)))

                  (value-type? x)
                  (-> tag-hash
                      (combine-hash (hash-type (:inner x)))
                      (combine-hash (hash (:value x))))

                  (type-var-type? x)
                  (combine-hash tag-hash (hash (:name x)))

                  (forall-type? x)
                  (-> tag-hash
                      (combine-hash (hash (:binder x)))
                      (combine-hash (hash-type (:body x))))

                  (sealed-dyn-type? x)
                  (combine-hash tag-hash (hash-type (:ground x)))

                  (conditional-type? x)
                  (combine-hash tag-hash
                                (reduce combine-hash
                                        1
                                        (map #(branch-type-hash hash-type %)
                                             (:branches x))))

                  :else
                  (hash x)))

              (map? x) (combine-hash (hash :map) (map-type-hash hash-type x))
              (sequential? x) (combine-hash (hash :sequential) (ordered-type-hash hash-type x))
              (set? x) (combine-hash (hash :set) (unordered-type-hash hash-type x))
              :else (hash x)))]
    (hash-type x)))

(defn- dedup-bucket
  [bucket t]
  (let [idx (first (keep-indexed (fn [idx candidate]
                                   (when (type=? candidate t)
                                     idx))
                                 bucket))]
    (if idx
      (assoc bucket idx t)
      (conj (or bucket []) t))))

(s/defn dedup-types :- #{s/Any}
  [types :- [s/Any]]
  (->> types
       (reduce (fn [acc t]
                 (update acc (type-hash t) dedup-bucket t))
               {})
       vals
       (apply concat)
       (into #{})))

(s/defn type-equal? :- s/Any
  [a :- s/Any
   b :- s/Any]
  (type=? (strip-runtime-closures a) (strip-runtime-closures b)))

(s/defn ref-display-form :- s/Symbol
  [ref :- s/Any]
  (cond
    (symbol? ref) ref
    (and (vector? ref)
         (seq (filter symbol? ref)))
    (last (filter symbol? ref))
    (keyword? ref) (symbol (name ref))
    (string? ref) (symbol ref)
    :else 'Unknown))

(s/defn placeholder-display-form :- s/Symbol
  [ref :- s/Any]
  (ref-display-form ref))
