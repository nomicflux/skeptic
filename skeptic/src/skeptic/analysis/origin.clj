(ns skeptic.analysis.origin
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.sum-types :as sums]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.analysis.value :as av]))

(defn- normalize-if-needed
  [type]
  (if (at/semantic-type-value? type)
    type
    (ato/normalize type)))

(defn- root-origin*
  [sym type]
  {:kind :root
   :sym sym
   :type type})

(defn- opaque-origin*
  [type]
  {:kind :opaque
   :type type})

(defn- plain-map?
  [value]
  (and (map? value) (not (record? value))))

(s/defn root-origin :- aos/RootOrigin
  [sym :- s/Any
   type :- ats/SemanticType]
  (root-origin* sym (normalize-if-needed type)))

(s/defn opaque-origin :- aos/Origin
  [type :- ats/SemanticType]
  (opaque-origin* (normalize-if-needed type)))

(s/defn map-key-lookup-origin :- aos/MapKeyLookupOrigin
  [root :- s/Any
   path :- s/Any
   defaults :- s/Any]
  {:kind :map-key-lookup
   :root root
   :path path
   :defaults defaults})

(s/defn branch-origin :- aos/BranchOrigin
  [test :- s/Any
   then-origin :- s/Any
   else-origin :- s/Any]
  {:kind :branch
   :test test
   :then-origin then-origin
   :else-origin else-origin})

(s/defn root-origin-value :- s/Any
  [origin :- s/Any]
  (when (= :root (:kind origin))
    origin))

(s/defn map-key-lookup-origin-value :- s/Any
  [origin :- s/Any]
  (when (= :map-key-lookup (:kind origin))
    origin))

(s/defn truthy-local-assumption :- aos/TruthyLocalAssumption
  [root :- s/Any
   polarity :- s/Bool]
  {:kind :truthy-local
   :root root
   :polarity polarity})

(s/defn blank-check-assumption :- aos/BlankCheckAssumption
  [root :- s/Any
   polarity :- s/Bool]
  {:kind :blank-check
   :root root
   :polarity polarity})

(s/defn contains-key-assumption :- aos/ContainsKeyAssumption
  [root :- s/Any
   key :- s/Any
   polarity :- s/Bool]
  {:kind :contains-key
   :root root
   :key key
   :polarity polarity})

(s/defn type-predicate-assumption :- aos/TypePredicateAssumption
  [root :- s/Any
   pred-info :- aos/PredInfo
   polarity :- s/Bool]
  (cond-> {:kind :type-predicate
           :root root
           :pred (:pred pred-info)
           :polarity polarity}
    (:class pred-info) (assoc :class (:class pred-info))))

(s/defn value-equality-assumption :- aos/ValueEqualityAssumption
  [root :- s/Any
   values :- [s/Any]
   polarity :- s/Bool]
  {:kind :value-equality
   :root root
   :values values
   :polarity polarity})

(s/defn path-value-equality-assumption :- aos/PathValueEqualityAssumption
  [root :- s/Any
   path :- s/Any
   values :- [s/Any]
   polarity :- s/Bool]
  {:kind :path-value-equality
   :root root
   :path path
   :values values
   :polarity polarity})

(s/defn path-type-predicate-assumption :- aos/PathTypePredicateAssumption
  [root :- s/Any
   path :- s/Any
   pred-info :- aos/PredInfo
   polarity :- s/Bool]
  (cond-> {:kind :path-type-predicate
           :root root
           :path path
           :pred (:pred pred-info)
           :polarity polarity}
    (:class pred-info) (assoc :class (:class pred-info))))

(s/defn conjunction-assumption :- aos/ConjunctionAssumption
  [parts :- s/Any]
  {:kind :conjunction
   :parts (vec parts)})

(s/defn disjunction-assumption :- aos/DisjunctionAssumption
  [parts :- s/Any]
  {:kind :disjunction
   :parts (vec parts)})

(def ^:private contradicted-assumption
  {:kind :contradicted})

(defn- invertible-assumption?
  [assumption]
  (contains? #{:type-predicate
               :truthy-local
               :boolean-proposition
               :contains-key
               :blank-check
               :value-equality
               :path-value-equality
               :path-type-predicate
               :conditional-branch}
             (:kind assumption)))

(defn- flip-assumption-polarity
  [assumption]
  (update assumption :polarity not))

(defn- negate-conjunct-list
  [conjuncts]
  (when (every? invertible-assumption? conjuncts)
    (disjunction-assumption (mapv flip-assumption-polarity conjuncts))))

(s/defn opposite-polarity :- (s/maybe aos/Assumption)
  [assumption :- aos/Assumption]
  (case (:kind assumption)
    :conjunction (negate-conjunct-list (:parts assumption))
    (:disjunction :contradicted) nil
    (flip-assumption-polarity assumption)))

(s/defn invert-assumption :- (s/maybe aos/Assumption)
  [assumption :- aos/Assumption]
  (when (invertible-assumption? assumption)
    (opposite-polarity assumption)))

(s/defn node-origin :- (s/maybe aos/Origin)
  [node :- s/Any]
  (or (aapi/node-origin node)
      (when-let [type (aapi/node-type node)]
        (opaque-origin type))))

(s/defn same-assumption? :- s/Bool
  [left :- aos/Assumption
   right :- aos/Assumption]
  (and (= (:kind left) (:kind right))
       (= (get-in left [:root :sym]) (get-in right [:root :sym]))
       (= (:polarity left) (:polarity right))
       (case (:kind left)
         :truthy-local true
         :boolean-proposition (= (:expr left) (:expr right))
         :blank-check true
         :contains-key (= (:key left) (:key right))
         :type-predicate (and (= (:pred left) (:pred right))
                              (= (:class left) (:class right)))
         :value-equality (= (:values left) (:values right))
         :path-value-equality (and (= (:path left) (:path right))
                                   (= (:values left) (:values right)))
         :path-type-predicate (and (= (:path left) (:path right))
                                   (= (:pred left) (:pred right))
                                   (= (:class left) (:class right)))
         :conditional-branch (at/type-equal? (:narrowed-type left) (:narrowed-type right))
         :conjunction (and (= :conjunction (:kind right))
                           (= (count (:parts left)) (count (:parts right)))
                           (every? true? (map same-assumption? (:parts left) (:parts right))))
         :disjunction (and (= :disjunction (:kind right))
                           (= (count (:parts left)) (count (:parts right)))
                           (every? true? (map same-assumption? (:parts left) (:parts right))))
         :contradicted true
         false)))

(s/defn opposite-assumption? :- s/Bool
  [left :- aos/Assumption
   right :- aos/Assumption]
  (if-let [r (opposite-polarity right)]
    (same-assumption? left r)
    false))

(s/defn same-assumption-proposition? :- s/Bool
  "Same narrowed fact on the same root, ignoring branch polarity."
  [a :- aos/Assumption
   b :- aos/Assumption]
  (and (= (:kind a) (:kind b))
       (= (get-in a [:root :sym]) (get-in b [:root :sym]))
       (case (:kind a)
         :truthy-local true
         :boolean-proposition (= (:expr a) (:expr b))
         :blank-check true
         :contains-key (= (:key a) (:key b))
         :type-predicate (and (= (:pred a) (:pred b))
                              (= (:class a) (:class b)))
         :value-equality (= (:values a) (:values b))
         :path-value-equality (and (= (:path a) (:path b))
                                   (= (:values a) (:values b)))
         :path-type-predicate (and (= (:path a) (:path b))
                                   (= (:pred a) (:pred b))
                                   (= (:class a) (:class b)))
         :conditional-branch (at/type-equal? (:narrowed-type a) (:narrowed-type b))
         :conjunction (and (= :conjunction (:kind b))
                           (= (count (:parts a)) (count (:parts b)))
                           (every? true? (map same-assumption-proposition? (:parts a) (:parts b))))
         :disjunction (and (= :disjunction (:kind b))
                           (= (count (:parts a)) (count (:parts b)))
                           (every? true? (map same-assumption-proposition? (:parts a) (:parts b))))
         :contradicted true
         false)))

(s/defn assumption-root? :- s/Bool
  [assumption :- aos/Assumption
   root :- aos/RootOrigin]
  (= (get-in assumption [:root :sym]) (:sym root)))

(defn- assumption-applies?
  [assumption root]
  (or (= :contradicted (:kind assumption))
      (assumption-root? assumption root)))

(s/defn apply-assumption-to-root-type :- ats/SemanticType
  [type :- ats/SemanticType
   assumption :- aos/Assumption]
  (letfn [(non-blank-string-type [t]
            (let [non-nil (an/partition-type-for-predicate t {:pred :some?} true)]
              (an/partition-type-for-predicate non-nil {:pred :string?} true)))]
    (case (:kind assumption)
      :contradicted
      (at/BottomType (ato/derive-prov type))

      :truthy-local
      (an/apply-truthy-local type (:polarity assumption))

      :blank-check
      (if (:polarity assumption)
        type
        (non-blank-string-type type))

      :contains-key
      (amo/refine-by-contains-key type (:key assumption) (:polarity assumption))

      :type-predicate
      (an/partition-type-for-predicate type
                                       {:pred (:pred assumption)
                                        :class (:class assumption)}
                                       (:polarity assumption))

      :value-equality
      (an/partition-type-for-values type (:values assumption) (:polarity assumption))

      :path-value-equality
      (amo/refine-map-path-by-values type (:path assumption) (:values assumption) (:polarity assumption))

      :path-type-predicate
      (amo/refine-map-path-by-predicate type
                                        (:path assumption)
                                        {:pred (:pred assumption) :class (:class assumption)}
                                        (:polarity assumption))

      :conditional-branch
      (:narrowed-type assumption)

      type)))

(s/defn refine-root-type :- ats/SemanticType
  [root :- aos/RootOrigin
   assumptions :- [aos/Assumption]]
  (reduce (fn [type assumption]
            (if (assumption-applies? assumption root)
              (apply-assumption-to-root-type type assumption)
              type))
          (:type root)
          assumptions))

(s/defn assumption-base-type :- ats/SemanticType
  [assumption :- aos/RootedAssumption
   assumptions :- [aos/Assumption]]
  (let [same-proposition? #(same-assumption-proposition? % assumption)]
    (refine-root-type (:root assumption)
                      (remove same-proposition? assumptions))))

(s/defn ^:private type-predicate-classification :- s/Any
  [base :- ats/SemanticType
   pred-info :- aos/PredInfo]
  (let [pos (an/partition-type-for-predicate base pred-info true)
        neg (an/partition-type-for-predicate base pred-info false)]
    (cond
      (and (not (at/bottom-type? pos)) (at/bottom-type? neg)) :always
      (and (at/bottom-type? pos) (not (at/bottom-type? neg))) :never
      (sums/exhausted-by-types? base [pos]) :always
      (sums/exhausted-by-types? base [neg]) :never
      :else :unknown)))

(s/defn ^:private value-in-values-classification :- s/Any
  [base :- ats/SemanticType
   values :- [s/Any]]
  (let [pos (an/partition-type-for-values base values true)
        neg (an/partition-type-for-values base values false)]
    (cond
      (at/bottom-type? pos) :never
      (at/bottom-type? neg) :always
      :else :unknown)))

(s/defn ^:private value-not-in-values-classification :- s/Any
  [base :- ats/SemanticType
   values :- [s/Any]]
  (let [pos (an/partition-type-for-values base values true)
        neg (an/partition-type-for-values base values false)]
    (cond
      (at/bottom-type? neg) :never
      (at/bottom-type? pos) :always
      :else :unknown)))

(defn- atom-key
  [a]
  (case (:kind a)
    :boolean-proposition {:kind :boolean-proposition
                          :root-sym (get-in a [:root :sym])
                          :disc (:expr a)}
    :type-predicate {:kind :type-predicate
                     :root-sym (get-in a [:root :sym])
                     :disc {:pred (:pred a) :class (:class a)}}
    :value-equality {:kind :value-equality
                     :root-sym (get-in a [:root :sym])
                     :disc (:values a)}))

(defn- assumption->formula
  [assumption]
  (case (:kind assumption)
    (:boolean-proposition :type-predicate :value-equality)
    {:kind :atom :expr (atom-key assumption) :polarity (:polarity assumption)}

    :conjunction
    {:kind :conjunction :parts (vec (keep assumption->formula (:parts assumption)))}

    :disjunction
    (let [parts (mapv assumption->formula (:parts assumption))]
      (when (every? some? parts)
        {:kind :disjunction :parts parts}))

    nil))

(defn- negate-formula
  [f]
  (case (:kind f)
    :atom (update f :polarity not)
    :conjunction {:kind :disjunction :parts (mapv negate-formula (:parts f))}
    :disjunction {:kind :conjunction :parts (mapv negate-formula (:parts f))}))

(s/defn assumption-truth :- aos/AssumptionTruth
  [assumption :- aos/Assumption
   assumptions :- [aos/Assumption]]
  (cond
    (some #(same-assumption? assumption %) assumptions) :true
    (some #(opposite-assumption? assumption %) assumptions) :false

    :else
    (case (:kind assumption)
      :contains-key
      (case (avc/contains-key-type-classification (assumption-base-type assumption assumptions)
                                                  (:key assumption))
        :always (if (:polarity assumption) :true :false)
        :never (if (:polarity assumption) :false :true)
        :unknown :unknown)

      :type-predicate
      (let [base (assumption-base-type assumption assumptions)
            pred-info {:pred (:pred assumption) :class (:class assumption)}]
        (case (type-predicate-classification base pred-info)
          :always (if (:polarity assumption) :true :false)
          :never (if (:polarity assumption) :false :true)
          :unknown :unknown))

      :path-type-predicate
      (let [root-base (refine-root-type (:root assumption)
                                        (remove #(same-assumption-proposition? % assumption) assumptions))
            base (amo/map-type-at-path root-base (:path assumption))
            pred-info {:pred (:pred assumption) :class (:class assumption)}]
        (if (nil? base)
          :unknown
          (case (type-predicate-classification base pred-info)
            :always (if (:polarity assumption) :true :false)
            :never (if (:polarity assumption) :false :true)
            :unknown :unknown)))

      :value-equality
      (let [base (assumption-base-type assumption assumptions)
            vals (:values assumption)]
        (if (:polarity assumption)
          (case (value-in-values-classification base vals)
            :always :true
            :never :false
            :unknown :unknown)
          (case (value-not-in-values-classification base vals)
            :always :true
            :never :false
            :unknown :unknown)))

      :conditional-branch
      :unknown

      :truthy-local
      :unknown

      :blank-check
      :unknown

      :conjunction
      (let [ts (mapv #(assumption-truth % assumptions) (:parts assumption))]
        (cond (every? #{:true} ts) :true
              (some #{:false} ts) :false
              :else :unknown))

      :disjunction
      (let [ts (mapv #(assumption-truth % assumptions) (:parts assumption))]
        (cond (some #{:true} ts) :true
              (every? #{:false} ts) :false
              :else :unknown))

	      (let [q  (assumption->formula assumption)
	            cs (keep assumption->formula assumptions)]
	        (cond
	          (and q (sums/formulas-cover? (cons q (mapv negate-formula cs)))) :true
	          (and q (sums/formulas-cover? (cons (negate-formula q) (mapv negate-formula cs)))) :false
	          :else :unknown)))))

(defn- expand-assumptions-once
  [assumptions]
  (vec
   (mapcat
    (fn [assumption]
      (case (:kind assumption)
        :conjunction
        (:parts assumption)

        :disjunction
        (let [other-assumptions (filterv #(not (identical? assumption %)) assumptions)
              survivors (filterv #(not= :false (assumption-truth % other-assumptions))
                                  (:parts assumption))]
          (case (count survivors)
            0 [contradicted-assumption]
            1 survivors
            [assumption]))

        [assumption]))
    assumptions)))

(s/defn simplify-assumptions :- [aos/Assumption]
  "Simplify environment assumptions. This is a pure query pass: it asks
  `assumption-truth`, which may reach type refinement, but it does not mutate
  state or recursively invoke simplification."
  [assumptions :- [aos/Assumption]]
  (loop [current (vec assumptions)]
    (let [next (expand-assumptions-once current)]
      (if (= next current)
        current
        (recur next)))))

(s/defn origin-type :- ats/SemanticType
  [origin :- aos/Origin
   assumptions :- [aos/Assumption]]
  (let [t (case (:kind origin)
            :root (refine-root-type origin assumptions)
            :opaque (:type origin)
            :map-key-lookup (reduce (fn [t [key default]]
                                      (amo/map-get-type t key default))
                                    (refine-root-type (:root origin) assumptions)
                                    (map vector (:path origin) (:defaults origin)))
            :branch (case (assumption-truth (:test origin) assumptions)
                      :true (origin-type (:then-origin origin) assumptions)
                      :false (origin-type (:else-origin origin) assumptions)
                      (let [then-t (origin-type (:then-origin origin) assumptions)
                            else-t (origin-type (:else-origin origin) assumptions)]
                        (av/join (ato/derive-prov then-t else-t) [then-t else-t])))
            (:type origin))]
    (if-some [binding-sym (:binding-sym origin)]
      (refine-root-type (root-origin binding-sym t) assumptions)
      t)))

(defn- local-type-and-origin
  [ctx sym entry]
  (cond
    (plain-map? entry)
    (let [t (normalize-if-needed (or (:type entry) (aapi/dyn ctx)))
          origin (or (:origin entry) (root-origin* sym t))]
      [t origin])

    (at/semantic-type-value? entry)
    [entry (root-origin* sym entry)]

    :else
    (let [d (aapi/dyn ctx)]
      [d (root-origin* sym d)])))

(s/defn effective-type :- ats/SemanticType
  [ctx :- s/Any
   sym :- s/Any
   entry :- s/Any
   assumptions :- [aos/Assumption]]
  (let [[t origin] (local-type-and-origin ctx sym entry)
        refined (or (some-> origin (origin-type assumptions)) t)]
    (normalize-if-needed refined)))

(s/defn local-root-origin :- (s/maybe aos/RootOrigin)
  [ctx :- s/Any
   node :- s/Any]
  (let [origin (node-origin node)]
    (if-let [root (root-origin-value origin)]
      root
      (when (aapi/local-node? node)
        (root-origin (aapi/node-form node) (or (aapi/node-type node) (aapi/dyn ctx)))))))

(s/defn contains-key-test-assumption :- (s/maybe aos/Assumption)
  [ctx :- s/Any
   target-node :- s/Any
   key :- s/Any]
  (when-let [root (local-root-origin ctx target-node)]
    (contains-key-assumption root key true)))

(s/defn ^:private target-path-value-equality-assumption :- (s/maybe aos/PathValueEqualityAssumption)
  [target :- s/Any
   literal :- s/Any]
  (when-let [origin (map-key-lookup-origin-value (aapi/node-origin target))]
    (path-value-equality-assumption (:root origin)
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
          (when-let [root (local-root-origin ctx target)]
            (value-equality-assumption root [(ac/literal-node-value literal)] true))))))

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
  (when-let [origin (map-key-lookup-origin-value (node-origin target))]
    (path-type-predicate-assumption (:root origin) (:path origin) pred-info polarity)))

(s/defn test->assumption :- (s/maybe aos/Assumption)
  [ctx :- s/Any
   test-node :- s/Any]
  (let [op (aapi/node-op test-node)
        invoke? (= :invoke op)
        invoke-fn (when invoke? (:fn test-node))
        invoke-args (when invoke? (:args test-node))
        invoke-sym (when invoke? (ac/resolved-call-sym invoke-fn))
        invoke-pred-info (when invoke?
                           (ac/type-predicate-assumption-info-for-sym invoke-sym invoke-args))]
    (cond
      (aapi/stable-identity-node? test-node)
      (when-let [root (local-root-origin ctx test-node)]
        (truthy-local-assumption root true))

      (= :instance? op)
      (let [target (aapi/node-target test-node)
            cls (aapi/node-class test-node)]
        (when (class? cls)
          (if-let [root (when (aapi/stable-identity-node? target)
                          (local-root-origin ctx target))]
            (type-predicate-assumption root {:pred :instance? :class cls} true)

            (map-key-lookup-path-assumption target {:pred :instance? :class cls} true))))

      (and invoke? (contains? ac/not-call-syms invoke-sym))
      (when (= 1 (count invoke-args))
        (some-> (first invoke-args)
                (->> (test->assumption ctx))
                invert-assumption))

      (and invoke? (contains? ac/equality-call-syms invoke-sym))
      (let [[left right] invoke-args]
        (equality-value-assumption ctx left right))

      (and (= :static-call op)
           (ac/static-equality-call? test-node))
      (let [[left right] (aapi/call-args test-node)]
        (equality-value-assumption ctx left right))

      invoke-pred-info
      (let [targ (if (= :instance? (:pred invoke-pred-info))
                   (second invoke-args)
                   (first invoke-args))]
        (if-let [root (when (aapi/stable-identity-node? targ)
                        (local-root-origin ctx targ))]
          (type-predicate-assumption root invoke-pred-info true)

          (map-key-lookup-path-assumption targ invoke-pred-info true)))

      (and invoke? (contains? ac/blank-call-syms invoke-sym))
      (let [targ (first invoke-args)]
        (when-let [root (when (aapi/stable-identity-node? targ)
                          (local-root-origin ctx targ))]
          (blank-check-assumption root true)))

      (ac/keyword-invoke-on-local? test-node)
      (when-let [[kw target] (ac/keyword-invoke-kw-and-target test-node)]
        (when (keyword? kw)
          (contains-key-test-assumption ctx target kw)))

      (and invoke? (contains? ac/contains-call-syms invoke-sym))
      (let [[target-node key-node] invoke-args]
        (when (ac/literal-map-key? key-node)
          (let [key (ac/literal-node-value key-node)]
            (when (keyword? key)
              (contains-key-test-assumption ctx target-node key)))))

      (and (= :static-call op)
           (ac/static-contains-call? test-node))
      (let [[target-node key-node] (aapi/call-args test-node)]
        (when (ac/literal-map-key? key-node)
          (let [key (ac/literal-node-value key-node)]
            (when (keyword? key)
              (contains-key-test-assumption ctx target-node key)))))

      (and (= :static-call op)
           (ac/static-nil?-call? test-node))
      (when-let [targ (ac/static-nil?-target test-node)]
        (if-let [root (when (aapi/stable-identity-node? targ)
                        (local-root-origin ctx targ))]
          (type-predicate-assumption root {:pred :nil?} true)

          (map-key-lookup-path-assumption targ {:pred :nil?} true)))

      (aapi/let-node? test-node)
      (test->assumption ctx (aapi/node-body test-node))

      (aapi/if-node? test-node)
      (test->assumption ctx (aapi/then-node test-node))

      :else
      (boolean-proposition-assumption test-node))))

(s/defn local-binding-init-assumption :- (s/maybe aos/Assumption)
  [ctx :- s/Any
   test-node :- s/Any
   locals :- s/Any]
  (when (and (aapi/local-node? test-node) locals)
    (when-let [entry (get locals (aapi/node-form test-node))]
      (when-let [init (aapi/binding-init entry)]
        (test->assumption ctx init)))))

(defn- leaf-region-conjuncts
  [ctx node locals]
  (let [primary (test->assumption ctx node)
        init-a  (when locals (local-binding-init-assumption ctx node locals))
        then    (filterv some? [primary init-a])
        else    (filterv some? (map invert-assumption then))]
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
  "Return {:then-conjuncts [...] :else-conjuncts [...]} of conjuncts known
   true in each region (truthy/falsy) of node, derived structurally from
   let+if shapes. No and/or vocabulary: the only signal is which branch of
   an `if` carries the test-local. Truth tables:
     (if g g y) — false-region: ¬g ∧ ¬y (conjunction); true-region: ¬g ∨ ¬y (disjunction)
     (if g y g) — true-region:  g ∧ y  (conjunction); false-region: ¬g ∨ ¬y (disjunction)
   `alias-map` maps let-bound syms to their init expressions so when a
   let-bound local appears as the if-test we narrow on the underlying expression."
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
               then-disjunction (negate-conjunct-list else-conjuncts)]
           {:then-conjuncts (if then-disjunction [then-disjunction] [])
            :else-conjuncts else-conjuncts})

         (same-local? t b)
         (let [t-eff (effective-test-node t alias-map)
               t-r   (region-conjuncts ctx t-eff locals alias-map)
               a-r   (region-conjuncts ctx a     locals alias-map)
               then-conjuncts (vec (concat (:then-conjuncts t-r)
                                           (:then-conjuncts a-r)))
               else-disjunction (negate-conjunct-list then-conjuncts)]
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

(defn- refine-local-entry
  [ctx sym entry assumptions]
  (let [current-type (when (map? entry) (:type entry))
        refined-type (effective-type ctx sym entry assumptions)]
    (if (plain-map? entry)
      (if (identical? current-type refined-type)
        entry
        (assoc entry :type refined-type))
      (if (at/semantic-type-value? entry)
        refined-type
        (assoc entry :type refined-type)))))

(s/defn refine-locals-for-assumption :- s/Any
  [ctx :- s/Any
   locals :- s/Any
   assumptions :- [aos/Assumption]]
  (into {}
        (map (fn [[sym entry]]
               [sym (refine-local-entry ctx sym entry assumptions)]))
        locals))

(s/defn branch-local-envs :- aos/BranchEnvs
  [ctx :- s/Any
   locals :- s/Any
   assumptions :- [aos/Assumption]
   conjuncts :- aos/Conjuncts]
  (let [{:keys [then-conjuncts else-conjuncts]} conjuncts
        then-assumptions (simplify-assumptions (into (vec assumptions) then-conjuncts))
        else-assumptions (simplify-assumptions (into (vec assumptions) else-conjuncts))]
    (if (= then-assumptions else-assumptions)
      (let [refined-locals (refine-locals-for-assumption ctx locals then-assumptions)]
        {:then-locals refined-locals
         :then-assumptions then-assumptions
         :else-locals refined-locals
         :else-assumptions else-assumptions})
      {:then-locals (refine-locals-for-assumption ctx locals then-assumptions)
       :then-assumptions then-assumptions
       :else-locals (refine-locals-for-assumption ctx locals else-assumptions)
       :else-assumptions else-assumptions})))

(s/defn guard-assumption :- (s/maybe aos/Assumption)
  [stmt-node :- s/Any]
  (when (and (aapi/if-node? stmt-node)
             (= :branch (aapi/branch-origin-kind stmt-node)))
    (let [then-bottom? (at/bottom-type? (aapi/node-type (aapi/then-node stmt-node)))
          else-bottom? (at/bottom-type? (aapi/node-type (aapi/else-node stmt-node)))
          assumption   (aapi/branch-test-assumption stmt-node)]
      (when assumption
        (cond
          else-bottom? assumption
          then-bottom? (opposite-polarity assumption)
          :else nil)))))

(s/defn apply-guard-assumption :- s/Any
  [ctx :- s/Any
   assumption :- (s/maybe aos/Assumption)]
  (let [{:keys [locals assumptions]} ctx
        parts (if (and assumption (= :conjunction (:kind assumption)))
                (:parts assumption)
                (if assumption [assumption] []))
        new-assumptions (simplify-assumptions (into (vec assumptions) parts))]
    (assoc ctx
           :assumptions new-assumptions
           :locals (refine-locals-for-assumption ctx locals new-assumptions))))

(s/defn ^:private ground-classifying-pred-info :- (s/maybe aos/PredInfo)
  [type :- ats/SemanticType]
  (let [type (ato/normalize type)]
    (cond
      (at/numeric-dyn-type? type) {:pred :number?}
      (at/value-type? type) (when (nil? (:value type)) {:pred :nil?})
      (at/ground-type? type)
      (let [g (:ground type)]
        (cond
          (= g :str) {:pred :string?}
          (= g :keyword) {:pred :keyword?}
          (= g :int) {:pred :integer?}
          (= g :bool) {:pred :boolean?}
          (= g :symbol) {:pred :symbol?}
          (and (map? g) (:class g)) {:pred :instance? :class (:class g)})))))

(s/defn ^:private contract-root-origin :- (s/maybe aos/RootOrigin)
  [arg-node :- aas/AnnotatedNode]
  (let [origin (aapi/node-origin arg-node)]
    (cond
      (and origin (= :root (:kind origin)))
      (root-origin (:sym origin)
                   (or (:type origin)
                       (throw (ex-info "RootOrigin missing :type" {:origin origin}))))

      (aapi/local-node? arg-node)
      (when-let [type (aapi/node-type arg-node)]
        (when (at/semantic-type-value? type)
          (root-origin (aapi/node-form arg-node) type)))

      :else nil)))

(s/defn ^:private call-arg-contract-assumption :- (s/maybe aos/TypePredicateAssumption)
  [arg-node :- aas/AnnotatedNode
   arg-type :- ats/SemanticType]
  (when (aapi/stable-identity-node? arg-node)
    (when-let [pred-info (ground-classifying-pred-info arg-type)]
      (when-let [root (contract-root-origin arg-node)]
        (type-predicate-assumption root pred-info true)))))

(s/defn call-arg-contract-assumptions :- [aos/TypePredicateAssumption]
  [node :- (s/maybe aas/AnnotatedNode)]
  (if-let [call (when (and node (aapi/call-node? node)) node)]
    (let [args (aapi/call-args call)
          expected-argtypes (aapi/call-expected-argtypes call)]
      (vec
       (keep (fn [[arg-node arg-type]]
               (call-arg-contract-assumption arg-node arg-type))
             (map vector args expected-argtypes))))
    []))
