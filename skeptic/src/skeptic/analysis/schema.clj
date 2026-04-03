(ns skeptic.analysis.schema
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.algebra :as aba]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema.cast-support :as ascs]
            [skeptic.analysis.schema.map-ops :as asm]
            [skeptic.analysis.schema.value-check :as asv]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(declare check-cast)

(defn candidate-value-cast-results
  [source-value target-entries path-key opts]
  (let [results (mapv (fn [target-entry]
                        (asv/with-map-path
                          (check-cast source-value (:value target-entry) opts)
                          path-key))
                      target-entries)]
    (if-let [success (some #(when (:ok? %) %) results)]
      [success]
      results)))

(defn exact-target-entry-cast-results
  [source-type target-type source-descriptor target-entry opts]
  (let [exact-value (:exact-value target-entry)
        source-candidates (asm/exact-key-candidates source-descriptor exact-value)
        source-exact-entry (asm/exact-key-entry source-descriptor exact-value)
        value-results (mapv (fn [source-entry]
                              (asv/with-map-path
                                (check-cast (:value source-entry) (:value target-entry) opts)
                                (:key target-entry)))
                            source-candidates)
        nullable-result (when (and (= :required-explicit (:kind target-entry))
                                   (= :optional-explicit (:kind source-exact-entry)))
                          (asv/with-map-path
                            (ascs/cast-fail source-type
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
        [(asv/with-map-path
           (ascs/cast-fail source-type
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
                               (filter #(asm/key-domain-covered? (ab/exact-value-import-type (:exact-value source-entry))
                                                                 (:inner-key-type %)))
                               vec)]
    (cond
      (empty? target-candidates)
      [(asv/with-map-path
         (ascs/cast-fail source-type
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
                                   (filter #(asm/key-domain-covered? source-key-type
                                                                     (:inner-key-type %)))
                                   vec)]
        (cond
          (empty? target-candidates)
          [(ascs/cast-fail source-type
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
  (let [source-descriptor (asm/map-entry-descriptor (:entries (ab/schema->type source-type)))
        target-descriptor (asm/map-entry-descriptor (:entries (ab/schema->type target-type)))
        target-exact-entries (vec (asm/effective-exact-entries target-descriptor))
        target-exact-values (set (map :exact-value target-exact-entries))
        target-schema-entries (vec (:schema-entries target-descriptor))
        source-exact-entries (vec (asm/effective-exact-entries source-descriptor))
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
          (ascs/with-cast-path (check-cast source-item target-item opts)
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
                    (ascs/with-cast-path
                      (ascs/cast-fail source-member
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
       (ascs/cast-ok source-type target-type :bottom-source)

       (= source-type target-type)
       (ascs/cast-ok source-type target-type :exact)

       (at/forall-type? target-type)
       (if (contains? (aba/type-free-vars source-type) (:binder target-type))
         (ascs/cast-fail source-type
                         target-type
                         :generalize
                         polarity
                         :forall-capture
                         []
                         {:binder (:binder target-type)
                          :cast-state (ascs/cast-state opts)})
         (let [child (check-cast source-type
                                 (:body target-type)
                                 (ascs/with-abstract-var opts (:binder target-type)))]
           (if (:ok? child)
             (ascs/cast-ok source-type
                           target-type
                           :generalize
                           [child]
                           {:binder (:binder target-type)
                            :cast-state (ascs/cast-state opts)})
             (ascs/cast-fail source-type
                             target-type
                             :generalize
                             polarity
                             :generalize-failed
                             [child]
                             {:binder (:binder target-type)
                              :cast-state (ascs/cast-state opts)}))))

       (at/forall-type? source-type)
       (let [instantiated (aba/type-substitute (:body source-type)
                                               (:binder source-type)
                                               at/Dyn)
             child (check-cast instantiated target-type
                               (ascs/with-nu-binding opts (:binder source-type) at/Dyn))]
         (if (:ok? child)
           (ascs/cast-ok source-type
                         target-type
                         :instantiate
                         [child]
                         {:binder (:binder source-type)
                          :instantiated-type instantiated
                          :cast-state (ascs/cast-state opts)})
           (ascs/cast-fail source-type
                           target-type
                           :instantiate
                           polarity
                           :instantiate-failed
                           [child]
                           {:binder (:binder source-type)
                            :instantiated-type instantiated
                            :cast-state (ascs/cast-state opts)})))

       (and (at/type-var-type? source-type)
            (at/dyn-type? target-type))
       (let [sealed-type (at/->SealedDynT source-type)]
         (ascs/cast-ok source-type
                       target-type
                       :seal
                       []
                       {:sealed-type sealed-type
                        :cast-state (:cast-state (ascs/register-seal opts sealed-type))}))

       (at/dyn-type? target-type)
       (ascs/cast-ok source-type target-type :target-dyn)

       (at/union-type? target-type)
       (let [children (ascs/indexed-cast-children :target-union-branch
                                                  #(check-cast source-type % opts)
                                                  (:members target-type))]
         (if-let [success (some #(when (:ok? %) %) children)]
           (ascs/cast-ok source-type target-type :target-union children {:chosen-rule (:rule success)})
           (ascs/cast-fail source-type target-type :target-union polarity :no-union-branch children)))

       (at/union-type? source-type)
       (let [children (ascs/indexed-cast-children :source-union-branch
                                                  #(check-cast % target-type opts)
                                                  (:members source-type))]
         (if (ascs/all-ok? children)
           (ascs/cast-ok source-type target-type :source-union children)
           (ascs/cast-fail source-type target-type :source-union polarity :source-branch-failed children)))

       (at/intersection-type? target-type)
       (let [children (ascs/indexed-cast-children :target-intersection-branch
                                                  #(check-cast source-type % opts)
                                                  (:members target-type))]
         (if (ascs/all-ok? children)
           (ascs/cast-ok source-type target-type :target-intersection children)
           (ascs/cast-fail source-type target-type :target-intersection polarity :target-component-failed children)))

       (at/intersection-type? source-type)
       (let [children (ascs/indexed-cast-children :source-intersection-branch
                                                  #(check-cast % target-type opts)
                                                  (:members source-type))]
         (if (ascs/all-ok? children)
           (ascs/cast-ok source-type target-type :source-intersection children)
           (ascs/cast-fail source-type target-type :source-intersection polarity :source-component-failed children)))

       (at/value-type? source-type)
       (if (asv/value-satisfies-type? (:value source-type) target-type)
         (ascs/cast-ok source-type target-type :value-exact)
         (ascs/cast-fail source-type target-type :value-exact polarity :exact-value-mismatch))

       (at/value-type? target-type)
       (if (asv/value-satisfies-type? (:value target-type) source-type)
         (ascs/cast-ok source-type target-type :target-value)
         (ascs/cast-fail source-type target-type :target-value polarity :target-value-mismatch))

       (and (at/maybe-type? source-type) (at/maybe-type? target-type))
       (let [child (ascs/with-cast-path (check-cast (:inner source-type) (:inner target-type) opts)
                     {:kind :maybe-value})]
         (if (:ok? child)
           (ascs/cast-ok source-type target-type :maybe-both [child])
           (ascs/cast-fail source-type target-type :maybe-both polarity :maybe-inner-failed [child])))

       (at/maybe-type? target-type)
       (let [child (ascs/with-cast-path (check-cast source-type (:inner target-type) opts)
                     {:kind :maybe-value})]
         (if (:ok? child)
           (ascs/cast-ok source-type target-type :maybe-target [child])
           (ascs/cast-fail source-type target-type :maybe-target polarity :maybe-target-inner-failed [child])))

       (at/maybe-type? source-type)
       (ascs/cast-fail source-type target-type :maybe-source polarity :nullable-source)

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
         (if (= (ascs/sealed-ground-name source-type) (aba/type-var-name target-type))
           (ascs/cast-ok source-type
                         target-type
                         :sealed-collapse
                         []
                         {:cast-state (ascs/cast-state opts)})
           (ascs/cast-fail source-type
                           target-type
                           :sealed-collapse
                           polarity
                           :sealed-ground-mismatch
                           []
                           {:cast-state (ascs/cast-state opts)}))

         (or (at/dyn-type? source-type)
             (at/placeholder-type? source-type))
         (ascs/cast-ok source-type
                       target-type
                       :type-var-target
                       []
                       {:cast-state (ascs/cast-state opts)})

         :else
         (ascs/cast-fail source-type
                         target-type
                         :type-var-target
                         polarity
                         :abstract-target-mismatch
                         []
                         {:cast-state (ascs/cast-state opts)}))

       (at/type-var-type? source-type)
       (ascs/cast-fail source-type
                       target-type
                       :type-var-source
                       polarity
                       :abstract-source-mismatch
                       []
                       {:cast-state (ascs/cast-state opts)})

       (at/sealed-dyn-type? source-type)
       (ascs/cast-fail source-type
                       target-type
                       :sealed-conflict
                       polarity
                       :sealed-mismatch
                       []
                       {:cast-state (ascs/cast-state opts)})

       (and (at/fun-type? source-type) (at/fun-type? target-type))
       (let [children (mapv (fn [target-method]
                              (if-let [source-method (ascs/matching-source-method source-type target-method)]
                                (let [domain-results (mapv (fn [idx target-input source-input]
                                                             (ascs/with-cast-path
                                                               (check-cast target-input
                                                                           source-input
                                                                           (update opts :polarity abr/flip-polarity))
                                                               {:kind :function-domain
                                                                :index idx}))
                                                           (range)
                                                           (:inputs target-method)
                                                           (:inputs source-method))
                                      range-result (ascs/with-cast-path
                                                     (check-cast (:output source-method)
                                                                 (:output target-method)
                                                                 opts)
                                                     {:kind :function-range})
                                      method-children (conj domain-results range-result)]
                                  (if (ascs/all-ok? method-children)
                                    (ascs/cast-ok source-method target-method :function-method method-children)
                                    (ascs/cast-fail source-method target-method :function-method polarity :function-component-failed method-children)))
                                (ascs/cast-fail source-type
                                                target-type
                                                :function-arity
                                                polarity
                                                :arity-mismatch
                                                []
                                                {:target-method target-method})))
                            (:methods target-type))]
         (if (ascs/all-ok? children)
           (ascs/cast-ok source-type target-type :function children)
           (ascs/cast-fail source-type target-type :function polarity :function-cast-failed children)))

       (and (at/map-type? source-type) (at/map-type? target-type))
       (let [children (map-cast-children source-type target-type opts)]
         (if (ascs/all-ok? children)
           (ascs/cast-ok source-type target-type :map children)
           (ascs/cast-fail source-type target-type :map polarity :map-cast-failed children)))

       (and (at/vector-type? source-type) (at/vector-type? target-type))
       (if-let [slot-count (vector-cast-slot-count source-type target-type)]
         (let [source-items (expand-vector-items source-type slot-count)
               target-items (expand-vector-items target-type slot-count)
               children (collection-cast-children :vector-index source-items target-items opts)]
           (if (ascs/all-ok? children)
             (ascs/cast-ok source-type target-type :vector children)
             (ascs/cast-fail source-type target-type :vector polarity :vector-element-failed children)))
         (ascs/cast-fail source-type target-type :vector polarity :vector-arity-mismatch))

       (and (at/seq-type? source-type) (at/seq-type? target-type))
       (let [source-items (:items source-type)
             target-items (:items target-type)]
         (if (= (count source-items) (count target-items))
           (let [children (collection-cast-children :seq-index source-items target-items opts)]
             (if (ascs/all-ok? children)
               (ascs/cast-ok source-type target-type :seq children)
               (ascs/cast-fail source-type target-type :seq polarity :seq-element-failed children)))
           (ascs/cast-fail source-type target-type :seq polarity :seq-arity-mismatch)))

       (and (at/set-type? source-type) (at/set-type? target-type))
       (let [source-members (:members source-type)
             target-members (:members target-type)]
         (if (= (count source-members) (count target-members))
           (let [children (set-cast-children source-members target-members opts)]
             (if (ascs/all-ok? children)
               (ascs/cast-ok source-type target-type :set children)
               (ascs/cast-fail source-type target-type :set polarity :set-element-failed children)))
           (ascs/cast-fail source-type target-type :set polarity :set-cardinality-mismatch)))

       (or (at/dyn-type? source-type)
           (at/placeholder-type? source-type))
       (ascs/cast-ok source-type target-type :residual-dynamic)

       (or (at/ground-type? source-type)
           (at/refinement-type? source-type)
           (at/adapter-leaf-type? source-type))
       (if (asv/leaf-overlap? source-type target-type)
         (ascs/cast-ok source-type target-type :leaf-overlap)
         (ascs/cast-fail source-type target-type :leaf-overlap polarity :leaf-mismatch))

       :else
       (ascs/cast-fail source-type target-type :mismatch polarity :mismatch)))))

(defn matches-map
  [expected actual-k actual-v]
  (let [expected (abc/canonicalize-schema expected)
        actual-v (abc/canonicalize-schema actual-v)
        descriptor (asm/map-entry-descriptor expected)
        key-query (asm/map-key-query actual-k)]
    (if (asm/exact-key-query? key-query)
      (every? (fn [exact-value]
                (some #(asm/nested-value-compatible? (:value %) actual-v)
                      (asm/exact-key-candidates descriptor exact-value)))
              [(:value key-query)])
      (some #(asm/nested-value-compatible? (:value %) actual-v)
            (filter (fn [entry]
                      (asm/key-domain-covered? (asm/query-key-type key-query)
                                               (:inner-key-type entry)))
                    (:schema-entries descriptor))))))

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
        (or (ascs/schema-equivalent? e v)
            (ascs/schema-equivalent? e s)
            (ascs/schema-equivalent? e (s/optional-key v))
            (ascs/schema-equivalent? e (s/optional-key s))
            (= (sb/check-if-schema e v) ::schema-valid)))

      (or (ascs/schema-equivalent? expected actual)
          (ascs/schema-equivalent? expected (s/optional-key actual))
          (= (sb/check-if-schema expected actual) ::schema-valid))
      true

      (and (map? expected) (map? actual))
      (every? (fn [[k v]] (matches-map expected k v)) actual)

      :else false)))

(defn get-by-matching-schema
  [m k]
  (asm/candidate-value-schema (asm/map-lookup-candidates m (asm/map-key-query k))))

(defn valued-get
  [m k]
  (get-by-matching-schema m k))

(defn required-key?
  [k]
  (= :required-explicit (asv/map-entry-kind k)))

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
