(ns skeptic.analysis.call-kinds.assumption
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.call-kinds.symbols :as symbols]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.sum-types :as sums]
            [skeptic.provenance :as prov]))

(s/defn ^:private target-path-value-equality-assumption :- (s/maybe aos/PathValueEqualityAssumption)
  [target :- s/Any
   literal :- s/Any]
  (when-let [origin (ao/map-key-lookup-origin-value (aapi/node-origin target))]
    (ao/path-value-equality-assumption (:root origin)
                                       (:path origin)
                                       [(ac/literal-node-value literal)]
                                       true)))

(s/defn ^:private equality-value-assumption :- (s/maybe aos/Assumption)
  [ctx :- s/Any
   left :- s/Any
   right :- s/Any]
  (let [[target literal] (cond
                           (and (aapi/stable-identity-node? left) (ac/literal-map-key? right)) [left right]
                           (and (aapi/stable-identity-node? right) (ac/literal-map-key? left)) [right left])]
    (when target
      (or (target-path-value-equality-assumption target literal)
          (when-let [root (ao/local-root-origin ctx target)]
            (ao/value-equality-assumption root [(ac/literal-node-value literal)] true))))))

(s/defn ^:private boolean-proposition-assumption :- (s/maybe aos/BooleanPropositionAssumption)
  [test-node :- s/Any]
  (let [type (aapi/node-type test-node)]
    (when (and type
               (sums/exhausted-by-values? type [true false])
               (not (aapi/stable-identity-node? test-node)))
      {:kind :boolean-proposition
       :expr (aapi/node-form test-node)
       :polarity true})))

(s/defn ^:private map-key-lookup-path-assumption :- (s/maybe aos/PathTypePredicateAssumption)
  [target :- s/Any
   pred-info :- aos/PredInfo
   polarity :- s/Bool]
  (when-let [origin (ao/map-key-lookup-origin-value (ao/node-origin target))]
    (ao/path-type-predicate-assumption (:root origin) (:path origin) pred-info polarity)))

(s/defn ^:private user-fn-summary-assumption :- (s/maybe aos/PathTypePredicateAssumption)
  [ctx       :- s/Any
   target    :- s/Any
   summary   :- {s/Keyword s/Any}
   polarity  :- s/Bool]
  (let [pred-info (cond-> {:pred (:pred summary)}
                    (:class summary) (assoc :class (:class summary)))
        target-type (or (aapi/node-type target) (aapi/dyn ctx))
        suffix-prov (prov/of target-type)
        suffix-queries (mapv #(amo/exact-key-query suffix-prov %) (:path summary))]
    (or (when-let [origin (ao/map-key-lookup-origin-value (ao/node-origin target))]
          (ao/path-type-predicate-assumption (:root origin)
                                             (into (vec (:path origin)) suffix-queries)
                                             pred-info
                                             polarity))
        (when-let [root (when (aapi/stable-identity-node? target)
                          (ao/local-root-origin ctx target))]
          (ao/path-type-predicate-assumption root suffix-queries pred-info polarity)))))

(s/defn ^:private contains-key-test-assumption :- (s/maybe aos/Assumption)
  [ctx :- s/Any
   target-node :- s/Any
   key :- s/Any]
  (when-let [root (ao/local-root-origin ctx target-node)]
    (ao/contains-key-assumption root key true)))

(declare call-test-assumption)

(s/defn ^:private local-binding-init-assumption :- (s/maybe aos/Assumption)
  [ctx :- s/Any
   test-node :- s/Any
   locals :- s/Any]
  (when (and (aapi/local-node? test-node) locals)
    (when-let [entry (get locals (aapi/node-form test-node))]
      (when-let [init (aapi/binding-init entry)]
        (call-test-assumption ctx init)))))

(s/defn call-test-assumption :- (s/maybe aos/Assumption)
  [ctx :- s/Any
   test-node :- s/Any]
  (let [op (aapi/node-op test-node)
        invoke? (= :invoke op)
        invoke-fn (when invoke? (:fn test-node))
        invoke-args (when invoke? (:args test-node))
        invoke-sym (when invoke? (ac/resolved-call-sym invoke-fn))
        invoke-pred-info (when invoke?
                           (ac/type-predicate-assumption-info-for-sym invoke-sym invoke-args))
        user-fn-summary (when (and invoke? (nil? invoke-pred-info))
                          (ac/user-fn-path-predicate-info invoke-sym))]
    (cond
      (aapi/stable-identity-node? test-node)
      (when-let [root (ao/local-root-origin ctx test-node)]
        (ao/truthy-local-assumption root true))

      (= :instance? op)
      (let [target (aapi/node-instance-target test-node)
            cls (aapi/node-class test-node)]
        (when (oracle/handle? cls)
          (or (map-key-lookup-path-assumption target {:pred :instance? :class cls} true)
              (when-let [root (when (aapi/stable-identity-node? target)
                                (ao/local-root-origin ctx target))]
                (ao/type-predicate-assumption root {:pred :instance? :class cls} true)))))

      (and invoke? (symbols/not? invoke-fn))
      (when (= 1 (count invoke-args))
        (some-> (first invoke-args)
                (->> (call-test-assumption ctx))
                ao/invert-assumption))

      (and invoke? (symbols/equality? invoke-fn))
      (let [[left right] invoke-args]
        (equality-value-assumption ctx left right))

      (and (= :static-call op)
           (symbols/static-equality? test-node))
      (let [[left right] (aapi/call-args test-node)]
        (equality-value-assumption ctx left right))

      invoke-pred-info
      (let [targ (if (= :instance? (:pred invoke-pred-info))
                   (second invoke-args)
                   (first invoke-args))]
        (or (map-key-lookup-path-assumption targ invoke-pred-info true)
            (when-let [root (when (aapi/stable-identity-node? targ)
                              (ao/local-root-origin ctx targ))]
              (ao/type-predicate-assumption root invoke-pred-info true))))

      (and user-fn-summary (= 1 (count invoke-args)))
      (user-fn-summary-assumption ctx (first invoke-args) user-fn-summary true)

      (and invoke? (symbols/blank? invoke-fn))
      (let [targ (first invoke-args)]
        (when-let [root (when (aapi/stable-identity-node? targ)
                          (ao/local-root-origin ctx targ))]
          (ao/blank-check-assumption root true)))

      (ac/keyword-invoke-on-local? test-node)
      (when-let [[kw target] (ac/keyword-invoke-kw-and-target test-node)]
        (when (keyword? kw)
          (contains-key-test-assumption ctx target kw)))

      (and invoke? (symbols/contains-call? invoke-fn))
      (let [[target-node key-node] invoke-args]
        (when (ac/literal-map-key? key-node)
          (let [key (ac/literal-node-value key-node)]
            (when (keyword? key)
              (contains-key-test-assumption ctx target-node key)))))

      (and (= :static-call op)
           (symbols/static-contains? test-node))
      (let [[target-node key-node] (aapi/call-args test-node)]
        (when (ac/literal-map-key? key-node)
          (let [key (ac/literal-node-value key-node)]
            (when (keyword? key)
              (contains-key-test-assumption ctx target-node key)))))

      (and (= :static-call op)
           (ac/static-nil?-call? test-node))
      (when-let [targ (ac/static-nil?-target test-node)]
        (or (map-key-lookup-path-assumption targ {:pred :nil?} true)
            (when-let [root (when (aapi/stable-identity-node? targ)
                              (ao/local-root-origin ctx targ))]
              (ao/type-predicate-assumption root {:pred :nil?} true))))

      (aapi/let-node? test-node)
      (call-test-assumption ctx (aapi/node-body test-node))

      (aapi/if-node? test-node)
      (call-test-assumption ctx (aapi/then-node test-node))

      :else
      (boolean-proposition-assumption test-node))))

(defn- leaf-region-conjuncts
  [ctx node locals]
  (let [primary (call-test-assumption ctx node)
        init-a  (when locals (local-binding-init-assumption ctx node locals))
        then    (filterv some? [primary init-a])
        else    (filterv some? (map ao/invert-assumption then))]
    {:then-conjuncts then
     :else-conjuncts else}))

(defn- effective-test-node
  [test-node alias-map]
  (or (when (aapi/local-node? test-node)
        (get alias-map (aapi/node-form test-node)))
      test-node))

(defn- same-local?
  [a b]
  (and (aapi/local-node? a)
       (aapi/local-node? b)
       (= (aapi/node-form a) (aapi/node-form b))))

(s/defn region-conjuncts :- aos/Conjuncts
  ([ctx :- s/Any
    node :- s/Any
    locals :- s/Any]
   (region-conjuncts ctx node locals {}))
  ([ctx :- s/Any
    node :- s/Any
    locals :- s/Any
    alias-map :- s/Any]
   (cond
     (and (aapi/let-node? node)
          (= 1 (count (aapi/node-bindings node))))
     (let [b    (first (aapi/node-bindings node))
           sym  (aapi/node-form b)
           init (aapi/node-init b)
           body (aapi/node-body node)]
       (region-conjuncts ctx body locals (assoc alias-map sym init)))

     (aapi/if-node? node)
     (let [t (aapi/node-test node)
           a (aapi/then-node node)
           b (aapi/else-node node)]
       (cond
         (same-local? t a)
         (let [t-eff (effective-test-node t alias-map)
               t-r   (region-conjuncts ctx t-eff locals alias-map)
               b-r   (region-conjuncts ctx b     locals alias-map)
               else-conjuncts (vec (concat (:else-conjuncts t-r)
                                           (:else-conjuncts b-r)))
               then-disjunction (ao/negate-conjunct-list else-conjuncts)]
           {:then-conjuncts (if then-disjunction [then-disjunction] [])
            :else-conjuncts else-conjuncts})

         (same-local? t b)
         (let [t-eff (effective-test-node t alias-map)
               t-r   (region-conjuncts ctx t-eff locals alias-map)
               a-r   (region-conjuncts ctx a     locals alias-map)
               then-conjuncts (vec (concat (:then-conjuncts t-r)
                                           (:then-conjuncts a-r)))
               else-disjunction (ao/negate-conjunct-list then-conjuncts)]
           {:then-conjuncts then-conjuncts
            :else-conjuncts (if else-disjunction [else-disjunction] [])})

         :else
         (leaf-region-conjuncts ctx (effective-test-node t alias-map) locals)))

     :else
     (leaf-region-conjuncts ctx node locals))))

(s/defn if-test-conjuncts :- aos/Conjuncts
  [ctx :- s/Any
   test-node :- s/Any
   locals :- s/Any]
  (region-conjuncts ctx test-node locals))
