(ns skeptic.analysis.schema
  (:require [clojure.set :as set]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.algebra :as aba]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at])
  (:import [schema.core Both CondPre ConditionalSchema Constrained Either EnumSchema EqSchema FnSchema Maybe NamedSchema One Schema]))

(declare optional-key-inner
         map-entry-kind
         value-satisfies-type?
         leaf-overlap?
         check-cast
         schema-compatible?
         schema-equivalent?
         valued-compatible?
         matches-map)

(defn finite-exact-key-values
  [type]
  (let [type (optional-key-inner (ab/schema->type type))]
    (cond
      (at/value-type? type)
      #{(:value type)}

      (at/union-type? type)
      (let [member-values (map finite-exact-key-values (:members type))]
        (when (every? set? member-values)
          (apply set/union member-values)))

      :else
      nil)))

(def map-key-query-tag
  ::map-key-query)

(defn map-key-query?
  [query]
  (at/tagged-map? query map-key-query-tag true))

(defn exact-key-query
  ([schema value]
   (exact-key-query schema value nil))
  ([schema value source-form]
   {map-key-query-tag true
    :kind :exact
    :schema (abc/canonicalize-schema schema)
    :value value
    :source-form source-form}))

(defn domain-key-query
  ([schema]
   (domain-key-query schema nil))
  ([schema source-form]
   {map-key-query-tag true
    :kind :domain
    :schema (abc/canonicalize-schema schema)
    :source-form source-form}))

(defn exact-key-query?
  [query]
  (and (map-key-query? query)
       (= :exact (:kind query))))

(defn map-key-query
  ([key]
   (map-key-query key nil))
  ([key source-form]
   (let [key (abl/localize-schema-value key)]
     (cond
       (map-key-query? key)
       (update key :schema abc/canonicalize-schema)

       (sb/valued-schema? key)
       (exact-key-query (:schema key) (:value key) (:value key))

       :else
       (let [exact-values (finite-exact-key-values key)]
         (if (and exact-values
                  (= 1 (count exact-values)))
           (exact-key-query key (first exact-values) source-form)
           (domain-key-query key source-form)))))))

(defn query-key-type
  [query]
  (if (exact-key-query? query)
    (ab/exact-value-import-type (:value query))
    (ab/schema->type (:schema query))))

(defn exact-entry-kind
  [key-type]
  (if (at/optional-key-type? key-type)
    :optional-explicit
    :required-explicit))

(defn descriptor-entry
  [entry-key entry-value kind]
  (let [entry-key (abc/canonicalize-schema entry-key)
        entry-value (abc/canonicalize-schema entry-value)
        key-type (ab/schema->type entry-key)
        inner-key-type (optional-key-inner key-type)]
    {:key entry-key
     :value entry-value
     :kind kind
     :key-type key-type
     :inner-key-type inner-key-type
     :exact-value (when (at/value-type? inner-key-type)
                    (:value inner-key-type))}))

(defn add-descriptor-entry
  [descriptor entry]
  (if-let [exact-value (:exact-value entry)]
    (case (:kind entry)
      :required-explicit (assoc-in descriptor [:required-exact exact-value] entry)
      :optional-explicit (assoc-in descriptor [:optional-exact exact-value] entry)
      (update descriptor :schema-entries conj entry))
    (update descriptor :schema-entries conj entry)))

(defn map-entry-descriptor
  [entries]
  (let [entries (abc/canonicalize-schema entries)]
    (reduce (fn [descriptor [entry-key entry-value]]
              (let [entry-key (abc/canonicalize-schema entry-key)
                    entry-value (abc/canonicalize-schema entry-value)
                    key-type (ab/schema->type entry-key)]
                (if-let [exact-values (finite-exact-key-values key-type)]
                  (reduce (fn [desc exact-value]
                            (add-descriptor-entry
                              desc
                              (descriptor-entry (ab/exact-value-import-type exact-value)
                                                entry-value
                                                (exact-entry-kind key-type))))
                          descriptor
                          exact-values)
                  (add-descriptor-entry
                    descriptor
                    (descriptor-entry entry-key
                                      entry-value
                                      (map-entry-kind entries entry-key))))))
            {:entries entries
             :required-exact {}
             :optional-exact {}
             :schema-entries []}
            entries)))

(defn effective-exact-entries
  [descriptor]
  (concat (vals (:required-exact descriptor))
          (->> (:optional-exact descriptor)
               (remove (fn [[value _entry]]
                         (contains? (:required-exact descriptor) value)))
               (map val))))

(defn exact-key-entry
  [descriptor exact-value]
  (or (get-in descriptor [:required-exact exact-value])
      (get-in descriptor [:optional-exact exact-value])))

(defn key-domain-covered?
  [source-key target-key]
  (let [source-key (optional-key-inner (ab/schema->type source-key))
        target-key (optional-key-inner (ab/schema->type target-key))]
    (cond
      (at/value-type? source-key)
      (value-satisfies-type? (:value source-key) target-key)

      (at/union-type? source-key)
      (every? #(key-domain-covered? % target-key) (:members source-key))

      (at/union-type? target-key)
      (some #(key-domain-covered? source-key %) (:members target-key))

      (at/maybe-type? source-key)
      (key-domain-covered? (:inner source-key) target-key)

      (at/maybe-type? target-key)
      (key-domain-covered? source-key (:inner target-key))

      (at/value-type? target-key)
      false

      :else
      (:ok? (check-cast source-key target-key)))))

(defn key-domain-overlap?
  [source-key target-key]
  (let [source-key (optional-key-inner (ab/schema->type source-key))
        target-key (optional-key-inner (ab/schema->type target-key))]
    (cond
      (or (at/dyn-type? source-key)
          (at/dyn-type? target-key)
          (at/placeholder-type? source-key)
          (at/placeholder-type? target-key))
      true

      (at/value-type? source-key)
      (value-satisfies-type? (:value source-key) target-key)

      (at/value-type? target-key)
      (value-satisfies-type? (:value target-key) source-key)

      (at/union-type? source-key)
      (some #(key-domain-overlap? % target-key) (:members source-key))

      (at/union-type? target-key)
      (some #(key-domain-overlap? source-key %) (:members target-key))

      (at/maybe-type? source-key)
      (key-domain-overlap? (:inner source-key) target-key)

      (at/maybe-type? target-key)
      (key-domain-overlap? source-key (:inner target-key))

      :else
      (or (:ok? (check-cast source-key target-key))
          (:ok? (check-cast target-key source-key))
          (leaf-overlap? source-key target-key)))))

(defn exact-key-candidates
  [descriptor exact-value]
  (if-let [entry (exact-key-entry descriptor exact-value)]
    [entry]
    (->> (:schema-entries descriptor)
         (filter #(value-satisfies-type? exact-value (:inner-key-type %)))
         vec)))

(defn domain-key-candidates
  [descriptor key-type]
  (let [key-type (optional-key-inner (ab/schema->type key-type))]
    (vec
      (concat
        (filter #(value-satisfies-type? (:exact-value %) key-type)
                (effective-exact-entries descriptor))
        (filter #(key-domain-overlap? key-type (:inner-key-type %))
                (:schema-entries descriptor))))))

(defn map-lookup-candidates
  [entries key-query]
  (let [descriptor (map-entry-descriptor entries)
        key-query (map-key-query key-query)]
    (if (exact-key-query? key-query)
      (exact-key-candidates descriptor (:value key-query))
      (domain-key-candidates descriptor (query-key-type key-query)))))

(defn candidate-value-schema
  [candidates]
  (when (seq candidates)
    (abc/schema-join (set (map (comp abc/semantic-value-schema :value) candidates)))))

(defn nested-value-compatible?
  [expected actual]
  (let [actual (abc/canonicalize-schema actual)]
    (if (sb/valued-schema? actual)
      (or (schema-compatible? expected (:value actual))
          (schema-compatible? expected (:schema actual)))
      (schema-compatible? expected actual))))

(def no-default ::no-default)

(defn map-get-schema
  ([m key]
   (map-get-schema m key no-default))
  ([m key default]
   (let [m (abc/canonicalize-schema m)
         key-query (map-key-query key)
         default-provided? (not= default no-default)
         default-schema (when default-provided?
                          (abc/canonicalize-schema default))]
     (cond
       (sb/maybe? m)
       (abc/schema-join
        [(map-get-schema (sb/de-maybe m) key-query default)
         (or default-schema (s/maybe s/Any))])

       (sb/join? m)
       (abc/schema-join (set (map #(map-get-schema % key-query default) (:schemas m))))

       (sb/plain-map-schema? m)
       (if-let [candidates (seq (map-lookup-candidates m key-query))]
         (let [base-value (candidate-value-schema candidates)
               base-value (if (and (exact-key-query? key-query)
                                   (= 1 (count candidates))
                                   (= :optional-explicit (:kind (first candidates)))
                                   (not default-provided?))
                            (abc/maybe-schema base-value)
                            base-value)]
           (if default-provided?
             (abc/schema-join [base-value default-schema])
             base-value))
         (if default-provided?
           default-schema
           s/Any))

       :else
       (if default-provided?
         (abc/schema-join [s/Any default-schema])
         s/Any)))))

(defn candidate-value-type
  [candidates]
  (when (seq candidates)
    (ab/union-type (map :value candidates))))

(defn map-get-type
  ([m key]
   (map-get-type m key no-default))
  ([m key default]
   (let [m (ab/schema->type m)
         key-query (map-key-query key)
         default-provided? (not= default no-default)
         default-type (when default-provided?
                        (ab/schema->type default))]
     (cond
       (at/maybe-type? m)
       (ab/union-type
        [(map-get-type (:inner m) key-query default)
         (or default-type (at/->MaybeT at/Dyn))])

       (at/union-type? m)
       (ab/union-type (map #(map-get-type % key-query default) (:members m)))

       (at/map-type? m)
       (if-let [candidates (seq (map-lookup-candidates (:entries m) key-query))]
         (let [base-value (candidate-value-type candidates)
               base-value (if (and (exact-key-query? key-query)
                                   (= 1 (count candidates))
                                   (= :optional-explicit (:kind (first candidates)))
                                   (not default-provided?))
                            (at/->MaybeT base-value)
                            base-value)]
           (if default-provided?
             (ab/union-type [base-value default-type])
             base-value))
         (if default-provided?
           default-type
           at/Dyn))

       :else
       (if default-provided?
         (ab/union-type [at/Dyn default-type])
         at/Dyn)))))

(defn merge-map-schemas
  [schemas]
  (let [schemas (mapv abc/canonicalize-schema schemas)]
    (if (every? sb/plain-map-schema? schemas)
      (reduce merge {} schemas)
      s/Any)))

(defn merge-map-types
  [types]
  (let [types (mapv ab/schema->type types)]
    (if (every? at/map-type? types)
      (at/->MapT (apply merge (map :entries types)))
      at/Dyn)))

(defn schema-equivalent?
  [expected actual]
  (= (abc/canonicalize-schema expected)
     (abc/canonicalize-schema actual)))

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
                                                         :witness-type (ab/schema->type witness-type)})
                              (update :abstract-vars conj binder))))

(defn register-seal
  [opts sealed-type]
  (assoc opts :cast-state (update (cast-state opts) :active-seals conj (ab/schema->type sealed-type))))

(defn sealed-ground-name
  [type]
  (some-> type ab/schema->type :ground aba/type-var-name))

(defn contains-sealed-ground?
  [type binder]
  (let [type (ab/schema->type type)]
    (cond
      (at/sealed-dyn-type? type)
      (= binder (sealed-ground-name type))

      (at/fn-method-type? type)
      (or (contains-sealed-ground? (:output type) binder)
          (some #(contains-sealed-ground? % binder) (:inputs type)))

      (at/fun-type? type)
      (some #(contains-sealed-ground? % binder) (:methods type))

      (at/maybe-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (or (at/union-type? type)
          (at/intersection-type? type))
      (some #(contains-sealed-ground? % binder) (:members type))

      (at/map-type? type)
      (some (fn [[k v]]
              (or (contains-sealed-ground? k binder)
                  (contains-sealed-ground? v binder)))
            (:entries type))

      (or (at/vector-type? type)
          (at/seq-type? type))
      (some #(contains-sealed-ground? % binder) (:items type))

      (at/set-type? type)
      (some #(contains-sealed-ground? % binder) (:members type))

      (at/var-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (at/value-type? type)
      (contains-sealed-ground? (:inner type) binder)

      (at/forall-type? type)
      (and (not= binder (:binder type))
           (contains-sealed-ground? (:body type) binder))

      :else
      false)))

(defn cast-result
  [{:keys [ok? source-type target-type rule polarity reason children details]}]
  (cond-> {:ok? ok?
           :blame-side (if ok? :none (abr/polarity->side polarity))
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
   (let [value-type (ab/schema->type value-type)
         ground-type (ab/schema->type ground-type)]
     (if (at/sealed-dyn-type? value-type)
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
   (let [type (ab/schema->type type)
         binder (or (aba/type-var-name (ab/schema->type binder))
                    binder)]
     (if (contains-sealed-ground? type binder)
       (cast-fail type
                  (at/->TypeVarT binder)
                  :nu-tamper
                  :global
                  :nu-tamper
                  []
                  {:cast-state (cast-state opts)})
       (cast-ok type
                (at/->TypeVarT binder)
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
  (if (at/optional-key-type? type)
    (:inner type)
    type))

(defn exact-value-type?
  [type]
  (at/value-type? (ab/schema->type type)))

(defn map-entry-kind
  ([entry-key]
   (let [entry-key (abc/canonicalize-schema entry-key)]
     (cond
       (and (not (at/semantic-type-value? entry-key))
            (s/optional-key? entry-key))
       :optional-explicit

       (and (not (at/semantic-type-value? entry-key))
            (s/specific-key? entry-key))
       :required-explicit

       :else
       (let [entry-type (ab/schema->type entry-key)
             inner (optional-key-inner entry-type)]
         (cond
           (and (at/optional-key-type? entry-type)
                (exact-value-type? inner))
           :optional-explicit

           (exact-value-type? inner)
           :required-explicit

           (and (at/optional-key-type? entry-type)
                (finite-exact-key-values inner))
           :optional-explicit

           (finite-exact-key-values inner)
           :required-explicit

           :else
           :extra-schema)))))
  ([entries entry-key]
   (let [entries (abc/canonicalize-schema entries)
         entry-key (abc/canonicalize-schema entry-key)
         typed-entries? (every? at/semantic-type-value? (keys entries))]
     (if (and (sb/plain-map-schema? entries)
              (not typed-entries?))
       (let [extra-key (s/find-extra-keys-schema entries)]
         (if (= entry-key extra-key)
           :extra-schema
           (map-entry-kind entry-key)))
       (map-entry-kind entry-key)))))

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

(defn map-contains-key-classification
  [type key]
  (let [descriptor (map-entry-descriptor (:entries (ab/schema->type type)))
        exact-entry (exact-key-entry descriptor key)]
    (if exact-entry
      (if (= :required-explicit (:kind exact-entry))
        :always
        :unknown)
      (if (seq (exact-key-candidates descriptor key))
        :unknown
        :never))))

(defn contains-key-classification
  [schema key]
  (let [type (ab/schema->type schema)]
    (cond
      (at/bottom-type? type) :never

      (at/maybe-type? type)
      (case (contains-key-classification (:inner type) key)
        :never :never
        :always :unknown
        :unknown :unknown)

      (at/map-type? type)
      (map-contains-key-classification type key)

      :else
      :unknown)))

(defn contains-key-type-classification
  [type key]
  (let [type (ab/schema->type type)]
    (if (at/union-type? type)
      (let [classifications (set (map #(contains-key-type-classification % key)
                                      (:members type)))]
        (cond
          (= #{:always} classifications) :always
          (= #{:never} classifications) :never
          :else :unknown))
      (contains-key-classification type key))))

(defn refine-schema-by-contains-key
  [schema key polarity]
  (let [schema (abc/canonicalize-schema schema)
        branches (or (abc/union-like-branches schema) #{schema})
        kept (->> branches
                  (keep (fn [branch]
                          (let [classification (contains-key-classification branch key)]
                            (case [polarity classification]
                              [true :never] nil
                              [false :always] nil
                              branch))))
                  set)]
    (cond
      (empty? kept) sb/Bottom
      (= 1 (count kept)) (first kept)
      :else (abc/schema-join kept))))

(defn refine-type-by-contains-key
  [type key polarity]
  (let [type (ab/schema->type type)
        branches (if (at/union-type? type)
                   (:members type)
                   #{type})
        kept (->> branches
                  (keep (fn [branch]
                          (let [classification (contains-key-type-classification branch key)]
                            (case [polarity classification]
                              [true :never] nil
                              [false :always] nil
                              branch))))
                  set)]
    (cond
      (empty? kept) at/BottomType
      (= 1 (count kept)) (first kept)
      :else (ab/union-type kept))))

(defn ground-accepts-value?
  [type value]
  (let [ground (:ground (ab/schema->type type))]
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
  (let [source-type (ab/schema->type source-type)
        target-type (ab/schema->type target-type)]
    (cond
      (at/ground-type? source-type)
      (cond
        (at/ground-type? target-type)
        (let [s (:ground source-type)
              t (:ground target-type)]
          (cond
            (= s t) true
            (and (map? s) (:class s) (map? t) (:class t))
            (or (.isAssignableFrom ^Class (:class s) ^Class (:class t))
                (.isAssignableFrom ^Class (:class t) ^Class (:class s)))
            :else false))

        (at/refinement-type? target-type)
        (leaf-overlap? source-type (:base target-type))

        (at/adapter-leaf-type? target-type)
        true

        :else false)

      (at/refinement-type? source-type)
      (leaf-overlap? (:base source-type) target-type)

      (at/adapter-leaf-type? source-type)
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
       (let [descriptor (map-entry-descriptor (:entries (ab/schema->type map-type)))
             required-missing (atom (set (keys (:required-exact descriptor))))]
         (and
          (every? (fn [[k v]]
                    (if-let [exact-entry (exact-key-entry descriptor k)]
                      (do
                        (swap! required-missing disj k)
                        (value-satisfies-type? v (:value exact-entry)))
                      (let [candidates (exact-key-candidates descriptor k)]
                        (and (seq candidates)
                             (some #(value-satisfies-type? v (:value %))
                                   candidates)))))
                  value)
          (empty? @required-missing)))))

(defn value-satisfies-type?
  [value type]
  (let [type (ab/schema->type type)]
    (cond
      (or (at/dyn-type? type)
          (at/placeholder-type? type))
      true

      (at/bottom-type? type)
      true

      (at/value-type? type)
      (= value (:value type))

      (at/ground-type? type)
      (ground-accepts-value? type value)

      (at/refinement-type? type)
      (and (value-satisfies-type? value (:base type))
           ((:accepts? type) value))

      (at/adapter-leaf-type? type)
      ((:accepts? type) value)

      (at/optional-key-type? type)
      (value-satisfies-type? value (:inner type))

      (at/maybe-type? type)
      (or (nil? value)
          (value-satisfies-type? value (:inner type)))

      (at/union-type? type)
      (some #(value-satisfies-type? value %) (:members type))

      (at/intersection-type? type)
      (every? #(value-satisfies-type? value %) (:members type))

      (at/map-type? type)
      (map-value-satisfies-type? value type)

      (at/vector-type? type)
      (and (vector? value)
           (if (:homogeneous? type)
             (every? #(value-satisfies-type? % (or (first (:items type)) at/Dyn))
                     value)
             (and (= (count value) (count (:items type)))
                  (every? true? (map value-satisfies-type? value (:items type))))))

      (at/seq-type? type)
      (and (sequential? value)
           (= (count value) (count (:items type)))
           (every? true? (map value-satisfies-type? value (:items type))))

      (at/set-type? type)
      (set-value-satisfies-type? value (:members type))

      (at/var-type? type)
      (and (var? value)
           (value-satisfies-type? @value (:inner type)))

      :else false)))

(declare check-cast)

(defn candidate-value-cast-results
  [source-value target-entries path-key opts]
  (let [results (mapv (fn [target-entry]
                        (with-map-path
                          (check-cast source-value (:value target-entry) opts)
                          path-key))
                      target-entries)]
    (if-let [success (some #(when (:ok? %) %) results)]
      [success]
      results)))

(defn exact-target-entry-cast-results
  [source-type target-type source-descriptor target-entry opts]
  (let [exact-value (:exact-value target-entry)
        source-candidates (exact-key-candidates source-descriptor exact-value)
        source-exact-entry (exact-key-entry source-descriptor exact-value)
        value-results (mapv (fn [source-entry]
                              (with-map-path
                                (check-cast (:value source-entry) (:value target-entry) opts)
                                (:key target-entry)))
                            source-candidates)
        nullable-result (when (and (= :required-explicit (:kind target-entry))
                                   (= :optional-explicit (:kind source-exact-entry)))
                          (with-map-path
                            (cast-fail source-type
                                       target-type
                                       :map-nullable-key
                                       (:polarity opts)
                                       :nullable-key
                                       []
                                       {:actual-key (:key source-exact-entry)
                                        :expected-key (:key target-entry)})
                            (:key target-entry)))]
    (cond
      (empty? source-candidates)
      (if (= :required-explicit (:kind target-entry))
        [(with-map-path
           (cast-fail source-type
                      target-type
                      :map-missing-key
                      (:polarity opts)
                      :missing-key
                      []
                      {:expected-key (:key target-entry)})
           (:key target-entry))]
        [])

      :else
      (cond-> value-results
        nullable-result (conj nullable-result)))))

(defn exact-source-entry-cast-results
  [source-type target-type source-entry target-schema-entries opts]
  (let [target-candidates (->> target-schema-entries
                               (filter #(key-domain-covered? (ab/exact-value-import-type (:exact-value source-entry))
                                                             (:inner-key-type %)))
                               vec)]
    (cond
      (empty? target-candidates)
      [(with-map-path
         (cast-fail source-type
                    target-type
                    :map-unexpected-key
                    (:polarity opts)
                    :unexpected-key
                    []
                    {:actual-key (:key source-entry)})
         (:key source-entry))]

      :else
      (candidate-value-cast-results (:value source-entry)
                                    target-candidates
                                    nil
                                    opts))))

(defn schema-domain-entry-cast-results
  [source-type target-type source-entry target-schema-entries opts]
  (let [source-key-type (:inner-key-type source-entry)]
    (cond
      (at/union-type? source-key-type)
      (mapcat (fn [member]
                (schema-domain-entry-cast-results source-type
                                                  target-type
                                                  (assoc source-entry
                                                         :key member
                                                         :key-type member
                                                         :inner-key-type member
                                                         :exact-value nil)
                                                  target-schema-entries
                                                  opts))
              (:members source-key-type))

      :else
      (let [target-candidates (->> target-schema-entries
                                   (filter #(key-domain-covered? source-key-type
                                                                 (:inner-key-type %)))
                                   vec)]
        (cond
          (empty? target-candidates)
          [(cast-fail source-type
                      target-type
                      :map-key-domain
                      (:polarity opts)
                      :map-key-domain-not-covered
                      []
                      {:actual-key (:key source-entry)
                       :source-key-domain source-key-type})]

          :else
          (candidate-value-cast-results (:value source-entry)
                                        target-candidates
                                        nil
                                        opts))))))

(defn map-cast-children
  [source-type target-type opts]
  (let [source-descriptor (map-entry-descriptor (:entries (ab/schema->type source-type)))
        target-descriptor (map-entry-descriptor (:entries (ab/schema->type target-type)))
        target-exact-entries (vec (effective-exact-entries target-descriptor))
        target-exact-values (set (map :exact-value target-exact-entries))
        target-schema-entries (vec (:schema-entries target-descriptor))
        source-exact-entries (vec (effective-exact-entries source-descriptor))
        source-extra-exact-entries (->> source-exact-entries
                                        (remove #(contains? target-exact-values
                                                            (:exact-value %)))
                                        vec)
        source-schema-entries (vec (:schema-entries source-descriptor))]
    (vec
      (concat
        (mapcat #(exact-target-entry-cast-results source-type
                                                  target-type
                                                  source-descriptor
                                                  %
                                                  opts)
                target-exact-entries)
        (mapcat #(exact-source-entry-cast-results source-type
                                                  target-type
                                                  %
                                                  target-schema-entries
                                                  opts)
                source-extra-exact-entries)
        (mapcat #(schema-domain-entry-cast-results source-type
                                                   target-type
                                                   %
                                                   target-schema-entries
                                                   opts)
                source-schema-entries)))))

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
      (vec (repeat slot-count (or (first items) at/Dyn)))
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
                                 (or (first target-members) at/Dyn)
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
   (let [source-type (ab/schema->type source-type)
         target-type (ab/schema->type target-type)
         opts (assoc opts :polarity polarity)]
     (cond
       (at/bottom-type? source-type)
       (cast-ok source-type target-type :bottom-source)

       (= source-type target-type)
       (cast-ok source-type target-type :exact)

       (at/forall-type? target-type)
       (if (contains? (aba/type-free-vars source-type) (:binder target-type))
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

       (at/forall-type? source-type)
       (let [instantiated (aba/type-substitute (:body source-type)
                                           (:binder source-type)
                                           at/Dyn)
             child (check-cast instantiated target-type
                               (with-nu-binding opts (:binder source-type) at/Dyn))]
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

       (and (at/type-var-type? source-type)
            (at/dyn-type? target-type))
       (let [sealed-type (at/->SealedDynT source-type)]
         (cast-ok source-type
                  target-type
                  :seal
                  []
                  {:sealed-type sealed-type
                   :cast-state (:cast-state (register-seal opts sealed-type))}))

       (at/dyn-type? target-type)
       (cast-ok source-type target-type :target-dyn)

       (at/union-type? target-type)
       (let [children (indexed-cast-children :target-union-branch
                                             #(check-cast source-type % opts)
                                             (:members target-type))]
         (if-let [success (some #(when (:ok? %) %) children)]
           (cast-ok source-type target-type :target-union children {:chosen-rule (:rule success)})
           (cast-fail source-type target-type :target-union polarity :no-union-branch children)))

       (at/union-type? source-type)
       (let [children (indexed-cast-children :source-union-branch
                                             #(check-cast % target-type opts)
                                             (:members source-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :source-union children)
           (cast-fail source-type target-type :source-union polarity :source-branch-failed children)))

       (at/intersection-type? target-type)
       (let [children (indexed-cast-children :target-intersection-branch
                                             #(check-cast source-type % opts)
                                             (:members target-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :target-intersection children)
           (cast-fail source-type target-type :target-intersection polarity :target-component-failed children)))

       (at/intersection-type? source-type)
       (let [children (indexed-cast-children :source-intersection-branch
                                             #(check-cast % target-type opts)
                                             (:members source-type))]
         (if (all-ok? children)
           (cast-ok source-type target-type :source-intersection children)
           (cast-fail source-type target-type :source-intersection polarity :source-component-failed children)))

       (at/value-type? source-type)
       (if (value-satisfies-type? (:value source-type) target-type)
           (cast-ok source-type target-type :value-exact)
           (cast-fail source-type target-type :value-exact polarity :exact-value-mismatch))

       (at/value-type? target-type)
       (if (value-satisfies-type? (:value target-type) source-type)
           (cast-ok source-type target-type :target-value)
           (cast-fail source-type target-type :target-value polarity :target-value-mismatch))

       (and (at/maybe-type? source-type) (at/maybe-type? target-type))
       (let [child (with-cast-path (check-cast (:inner source-type) (:inner target-type) opts)
                     {:kind :maybe-value})]
         (if (:ok? child)
           (cast-ok source-type target-type :maybe-both [child])
           (cast-fail source-type target-type :maybe-both polarity :maybe-inner-failed [child])))

       (at/maybe-type? target-type)
       (let [child (with-cast-path (check-cast source-type (:inner target-type) opts)
                     {:kind :maybe-value})]
         (if (:ok? child)
           (cast-ok source-type target-type :maybe-target [child])
           (cast-fail source-type target-type :maybe-target polarity :maybe-target-inner-failed [child])))

       (at/maybe-type? source-type)
       (cast-fail source-type target-type :maybe-source polarity :nullable-source)

       (at/optional-key-type? source-type)
       (check-cast (:inner source-type) target-type opts)

       (at/optional-key-type? target-type)
       (check-cast source-type (:inner target-type) opts)

       (at/var-type? source-type)
       (check-cast (:inner source-type) target-type opts)

       (at/var-type? target-type)
       (check-cast source-type (:inner target-type) opts)

       (at/type-var-type? target-type)
       (cond
         (at/sealed-dyn-type? source-type)
         (if (= (sealed-ground-name source-type) (aba/type-var-name target-type))
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

         (or (at/dyn-type? source-type)
             (at/placeholder-type? source-type))
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

       (at/type-var-type? source-type)
       (cast-fail source-type
                  target-type
                  :type-var-source
                  polarity
                  :abstract-source-mismatch
                  []
                  {:cast-state (cast-state opts)})

       (at/sealed-dyn-type? source-type)
       (cast-fail source-type
                  target-type
                  :sealed-conflict
                  polarity
                  :sealed-mismatch
                  []
                  {:cast-state (cast-state opts)})

       (and (at/fun-type? source-type) (at/fun-type? target-type))
       (let [children (mapv (fn [target-method]
                              (if-let [source-method (matching-source-method source-type target-method)]
                                (let [domain-results (mapv (fn [idx target-input source-input]
                                                             (with-cast-path
                                                               (check-cast target-input
                                                                           source-input
                                                                           (update opts :polarity abr/flip-polarity))
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

       (and (at/map-type? source-type) (at/map-type? target-type))
       (let [children (map-cast-children source-type target-type opts)]
         (if (all-ok? children)
           (cast-ok source-type target-type :map children)
           (cast-fail source-type target-type :map polarity :map-cast-failed children)))

       (and (at/vector-type? source-type) (at/vector-type? target-type))
       (if-let [slot-count (vector-cast-slot-count source-type target-type)]
         (let [source-items (expand-vector-items source-type slot-count)
               target-items (expand-vector-items target-type slot-count)
               children (collection-cast-children :vector-index source-items target-items opts)]
           (if (all-ok? children)
             (cast-ok source-type target-type :vector children)
             (cast-fail source-type target-type :vector polarity :vector-element-failed children)))
         (cast-fail source-type target-type :vector polarity :vector-arity-mismatch))

       (and (at/seq-type? source-type) (at/seq-type? target-type))
       (let [source-items (:items source-type)
             target-items (:items target-type)]
         (if (= (count source-items) (count target-items))
           (let [children (collection-cast-children :seq-index source-items target-items opts)]
             (if (all-ok? children)
               (cast-ok source-type target-type :seq children)
               (cast-fail source-type target-type :seq polarity :seq-element-failed children)))
           (cast-fail source-type target-type :seq polarity :seq-arity-mismatch)))

       (and (at/set-type? source-type) (at/set-type? target-type))
       (let [source-members (:members source-type)
             target-members (:members target-type)]
         (if (= (count source-members) (count target-members))
           (let [children (set-cast-children source-members target-members opts)]
             (if (all-ok? children)
               (cast-ok source-type target-type :set children)
               (cast-fail source-type target-type :set polarity :set-element-failed children)))
           (cast-fail source-type target-type :set polarity :set-cardinality-mismatch)))

       (or (at/dyn-type? source-type)
           (at/placeholder-type? source-type))
       (cast-ok source-type target-type :residual-dynamic)

       (or (at/ground-type? source-type)
           (at/refinement-type? source-type)
           (at/adapter-leaf-type? source-type))
       (if (leaf-overlap? source-type target-type)
         (cast-ok source-type target-type :leaf-overlap)
         (cast-fail source-type target-type :leaf-overlap polarity :leaf-mismatch))

       :else
       (cast-fail source-type target-type :mismatch polarity :mismatch)))))

(defn valued-compatible?
  [expected actual]
  (let [expected (abc/canonicalize-schema expected)
        actual (abc/canonicalize-schema actual)]
    (cond
      (sb/valued-schema? expected)
      (throw (IllegalArgumentException. "Only actual can be a valued schema"))

      (sb/valued-schema? actual)
      (let [v (:value actual)
            s (:schema actual)
            e (sb/de-maybe expected)]
        (or (schema-equivalent? e v)
            (schema-equivalent? e s)
            (schema-equivalent? e (s/optional-key v))
            (schema-equivalent? e (s/optional-key s))
            (= (sb/check-if-schema e v) ::schema-valid)))

      (or (schema-equivalent? expected actual)
          (schema-equivalent? expected (s/optional-key actual))
          (= (sb/check-if-schema expected actual) ::schema-valid))
      true

      (and (map? expected) (map? actual))
      (every? (fn [[k v]] (matches-map expected k v)) actual)

      :else false)))

(defn get-by-matching-schema
  [m k]
  (candidate-value-schema (map-lookup-candidates m (map-key-query k))))

(defn valued-get
  [m k]
  (get-by-matching-schema m k))

(declare matches-map)

(defn matches-map
  [expected actual-k actual-v]
  (let [expected (abc/canonicalize-schema expected)
        actual-v (abc/canonicalize-schema actual-v)
        descriptor (map-entry-descriptor expected)
        key-query (map-key-query actual-k)]
    (if (exact-key-query? key-query)
      (every? (fn [exact-value]
                (some #(nested-value-compatible? (:value %) actual-v)
                      (exact-key-candidates descriptor exact-value)))
              [(:value key-query)])
      (some #(nested-value-compatible? (:value %) actual-v)
            (filter (fn [entry]
                      (key-domain-covered? (query-key-type key-query)
                                           (:inner-key-type entry)))
                    (:schema-entries descriptor))))))

(defn required-key?
  [k]
  (= :required-explicit (map-entry-kind k)))

(defn schema-compatible?
  [expected actual]
  (:ok? (check-cast (ab/schema->type actual) (ab/schema->type expected))))

(defn schema-values
  [s]
  (cond
    (sb/valued-schema? s) [(:schema s) (:value s)]
    (and (map? s)
         (not (s/optional-key? s)))
    (let [{valued-schemas true base-schemas false} (->> s keys (group-by sb/valued-schema?))

          complex-keys (->> valued-schemas
                            (map (fn [k] (let [v (get s k)]
                                      (map (fn [k2]
                                             {k2 (if (and (abc/schema? k2) (sb/valued-schema? v))
                                                   (:schema v)
                                                   v)})
                                           (schema-values k)))))
                            sb/all-pairs
                            (map (partial into {})))

          complex-values (->> base-schemas
                              (map (fn [k]
                                     (let [v (get s k)]
                                       (map (fn [v2] {k v2})
                                            (if (sb/valued-schema? v) (schema-values v) [v])))))
                              sb/all-pairs
                              (map (partial into {})))

          split-keys (mapcat (fn [vs] (mapv #(merge vs %) complex-keys)) complex-values)]
      split-keys)
    :else [s]))

(s/defschema WithPlaceholder
  {s/Keyword s/Any})

(s/defschema ArgCount
  (s/cond-pre s/Int (s/eq :varargs)))

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
