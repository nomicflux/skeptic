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

(declare type=? type-hash)

(defn- ordered-type=?
  [a b]
  (and (= (count a) (count b))
       (every? true? (map type=? a b))))

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
  [a b]
  (and (= (count a) (count b))
       (loop [remaining (seq b)
              xs (seq a)]
         (if-not xs
           true
           (when-let [[_ remaining] (take-matching #(type=? (first xs) %) remaining)]
             (recur (seq remaining) (next xs)))))))

(defn- map-type=?
  [a b]
  (and (= (count a) (count b))
       (loop [remaining (seq b)
              entries (seq a)]
         (if-not entries
           true
           (let [[ak av] (first entries)]
             (when-let [[_ remaining]
                        (take-matching (fn [[bk bv]]
                                         (and (type=? ak bk)
                                              (type=? av bv)))
                                       remaining)]
               (recur (seq remaining) (next entries))))))))

(defn- branch-type=?
  [a b]
  (and (= (first a) (first b))
       (type=? (second a) (second b))))

(defn- ordered-branch-type=?
  [a b]
  (and (= (count a) (count b))
       (every? true? (map branch-type=? a b))))

(defn- semantic-type=?
  [a b]
  (and (= (semantic-tag a) (semantic-tag b))
       (cond
         (or (dyn-type? a)
             (bottom-type? a)
             (numeric-dyn-type? a))
         true

         (ground-type? a)
         (and (= (:ground a) (:ground b))
              (= (:display-form a) (:display-form b)))

         (refinement-type? a)
         (and (type=? (:base a) (:base b))
              (= (:display-form a) (:display-form b))
              (= (:accepts? a) (:accepts? b))
              (type=? (:adapter-data a) (:adapter-data b)))

         (adapter-leaf-type? a)
         (and (= (:adapter a) (:adapter b))
              (= (:display-form a) (:display-form b))
              (= (:accepts? a) (:accepts? b))
              (type=? (:adapter-data a) (:adapter-data b)))

         (optional-key-type? a)
         (type=? (:inner a) (:inner b))

         (fn-method-type? a)
         (and (ordered-type=? (:inputs a) (:inputs b))
              (type=? (:output a) (:output b))
              (= (:min-arity a) (:min-arity b))
              (= (:variadic? a) (:variadic? b))
              (= (:names a) (:names b)))

         (fun-type? a)
         (ordered-type=? (:methods a) (:methods b))

         (maybe-type? a)
         (type=? (:inner a) (:inner b))

         (union-type? a)
         (unordered-type=? (:members a) (:members b))

         (intersection-type? a)
         (unordered-type=? (:members a) (:members b))

         (map-type? a)
         (map-type=? (:entries a) (:entries b))

         (vector-type? a)
         (and (ordered-type=? (:items a) (:items b))
              (= (:homogeneous? a) (:homogeneous? b)))

         (set-type? a)
         (and (unordered-type=? (:members a) (:members b))
              (= (:homogeneous? a) (:homogeneous? b)))

         (seq-type? a)
         (and (ordered-type=? (:items a) (:items b))
              (= (:homogeneous? a) (:homogeneous? b)))

         (var-type? a)
         (type=? (:inner a) (:inner b))

         (placeholder-type? a)
         (= (:ref a) (:ref b))

         (inf-cycle-type? a)
         (= (:ref a) (:ref b))

         (value-type? a)
         (and (type=? (:inner a) (:inner b))
              (= (:value a) (:value b)))

         (type-var-type? a)
         (= (:name a) (:name b))

         (forall-type? a)
         (and (= (:binder a) (:binder b))
              (type=? (:body a) (:body b)))

         (sealed-dyn-type? a)
         (type=? (:ground a) (:ground b))

         (conditional-type? a)
         (ordered-branch-type=? (:branches a) (:branches b))

         :else
         (= a b))))

(defn type=?
  [a b]
  (cond
    (identical? a b) true
    (and (semantic-type-value? a)
         (semantic-type-value? b)) (semantic-type=? a b)
    (or (semantic-type-value? a)
        (semantic-type-value? b)) false
    (and (map? a) (map? b)) (map-type=? a b)
    (and (sequential? a) (sequential? b)) (ordered-type=? a b)
    (and (set? a) (set? b)) (unordered-type=? a b)
    :else (= a b)))

(defn- combine-hash
  [seed value]
  (unchecked-add-int (unchecked-multiply-int 31 seed) (int value)))

(defn- ordered-type-hash
  [xs]
  (reduce combine-hash 1 (map type-hash xs)))

(defn- unordered-type-hash
  [xs]
  (reduce unchecked-add-int 0 (map type-hash xs)))

(defn- map-type-hash
  [m]
  (reduce unchecked-add-int
          0
          (map (fn [[k v]]
                 (combine-hash (type-hash k) (type-hash v)))
               m)))

(defn- branch-type-hash
  [[pred typ]]
  (combine-hash (hash pred) (type-hash typ)))

(defn- type-hash
  [x]
  (cond
    (semantic-type-value? x)
    (let [tag-hash (hash (semantic-tag x))]
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
            (combine-hash (type-hash (:base x)))
            (combine-hash (hash (:display-form x)))
            (combine-hash (hash (:accepts? x)))
            (combine-hash (type-hash (:adapter-data x))))

        (adapter-leaf-type? x)
        (-> tag-hash
            (combine-hash (hash (:adapter x)))
            (combine-hash (hash (:display-form x)))
            (combine-hash (hash (:accepts? x)))
            (combine-hash (type-hash (:adapter-data x))))

        (optional-key-type? x)
        (combine-hash tag-hash (type-hash (:inner x)))

        (fn-method-type? x)
        (-> tag-hash
            (combine-hash (ordered-type-hash (:inputs x)))
            (combine-hash (type-hash (:output x)))
            (combine-hash (hash (:min-arity x)))
            (combine-hash (hash (:variadic? x)))
            (combine-hash (hash (:names x))))

        (fun-type? x)
        (combine-hash tag-hash (ordered-type-hash (:methods x)))

        (maybe-type? x)
        (combine-hash tag-hash (type-hash (:inner x)))

        (union-type? x)
        (combine-hash tag-hash (unordered-type-hash (:members x)))

        (intersection-type? x)
        (combine-hash tag-hash (unordered-type-hash (:members x)))

        (map-type? x)
        (combine-hash tag-hash (map-type-hash (:entries x)))

        (vector-type? x)
        (-> tag-hash
            (combine-hash (ordered-type-hash (:items x)))
            (combine-hash (hash (:homogeneous? x))))

        (set-type? x)
        (-> tag-hash
            (combine-hash (unordered-type-hash (:members x)))
            (combine-hash (hash (:homogeneous? x))))

        (seq-type? x)
        (-> tag-hash
            (combine-hash (ordered-type-hash (:items x)))
            (combine-hash (hash (:homogeneous? x))))

        (var-type? x)
        (combine-hash tag-hash (type-hash (:inner x)))

        (placeholder-type? x)
        (combine-hash tag-hash (hash (:ref x)))

        (inf-cycle-type? x)
        (combine-hash tag-hash (hash (:ref x)))

        (value-type? x)
        (-> tag-hash
            (combine-hash (type-hash (:inner x)))
            (combine-hash (hash (:value x))))

        (type-var-type? x)
        (combine-hash tag-hash (hash (:name x)))

        (forall-type? x)
        (-> tag-hash
            (combine-hash (hash (:binder x)))
            (combine-hash (type-hash (:body x))))

        (sealed-dyn-type? x)
        (combine-hash tag-hash (type-hash (:ground x)))

        (conditional-type? x)
        (combine-hash tag-hash (reduce combine-hash 1 (map branch-type-hash (:branches x))))

        :else
        (hash x)))

    (map? x) (combine-hash (hash :map) (map-type-hash x))
    (sequential? x) (combine-hash (hash :sequential) (ordered-type-hash x))
    (set? x) (combine-hash (hash :set) (unordered-type-hash x))
    :else (hash x)))

(defn- dedup-bucket
  [bucket t]
  (let [idx (first (keep-indexed (fn [idx candidate]
                                   (when (type=? candidate t)
                                     idx))
                                 bucket))]
    (if idx
      (assoc bucket idx t)
      (conj (or bucket []) t))))

(defn dedup-types
  [types]
  (->> types
       (reduce (fn [acc t]
                 (update acc (type-hash t) dedup-bucket t))
               {})
       vals
       (apply concat)
       (into #{})))

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
