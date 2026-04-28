(ns skeptic.analysis.origin
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
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

(s/defn root-origin
  [sym :- s/Any
   type :- ats/SemanticType]
  :- aos/RootOrigin
  {:kind :root
   :sym sym
   :type (ato/normalize type)})

(s/defn opaque-origin
  [type :- ats/SemanticType]
  :- aos/Origin
  {:kind :opaque
   :type (ato/normalize type)})

(s/defn node-origin
  [node :- s/Any]
  :- (s/maybe aos/Origin)
  (or (aapi/node-origin node)
      (when-let [type (aapi/node-type node)]
        (opaque-origin type))))

(s/defn opposite-polarity
  [assumption :- aos/Assumption]
  :- (s/maybe aos/Assumption)
  (case (:kind assumption)
    (:conjunction :disjunction) nil
    (update assumption :polarity not)))

(defn- invertible-assumption?
  [assumption]
  (contains? #{:type-predicate
               :boolean-proposition
               :contains-key
               :blank-check
               :value-equality
               :path-value-equality
               :path-type-predicate
               :conditional-branch}
             (:kind assumption)))

(s/defn invert-assumption
  [assumption :- aos/Assumption]
  :- (s/maybe aos/Assumption)
  (when (invertible-assumption? assumption)
    (opposite-polarity assumption)))

(defn- negate-conjunct-list
  [conjuncts]
  (when (every? invertible-assumption? conjuncts)
    {:kind :disjunction :parts (mapv invert-assumption conjuncts)}))

(s/defn same-assumption?
  [left :- aos/Assumption
   right :- aos/Assumption]
  :- s/Bool
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
         false)))

(s/defn opposite-assumption?
  [left :- aos/Assumption
   right :- aos/Assumption]
  :- s/Bool
  (if-let [r (opposite-polarity right)]
    (same-assumption? left r)
    false))

(s/defn same-assumption-proposition?
  "Same narrowed fact on the same root, ignoring branch polarity."
  [a :- aos/Assumption
   b :- aos/Assumption]
  :- s/Bool
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
         false)))

(s/defn assumption-root?
  [assumption :- aos/Assumption
   root :- aos/RootOrigin]
  :- s/Bool
  (= (get-in assumption [:root :sym]) (:sym root)))

(s/defn apply-assumption-to-root-type
  [type :- ats/SemanticType
   assumption :- aos/Assumption]
  :- ats/SemanticType
  (letfn [(non-blank-string-type [t]
            (let [non-nil (an/partition-type-for-predicate t {:pred :some?} true)]
              (an/partition-type-for-predicate non-nil {:pred :string?} true)))]
    (case (:kind assumption)
      :truthy-local
      (an/apply-truthy-local type (:polarity assumption))

      :blank-check
      (if (:polarity assumption)
        type
        (non-blank-string-type type))

      :contains-key
      (avc/refine-type-by-contains-key type (:key assumption) (:polarity assumption))

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

(s/defn refine-root-type
  [root :- aos/RootOrigin
   assumptions :- [aos/Assumption]]
  :- ats/SemanticType
  (reduce (fn [type assumption]
            (if (assumption-root? assumption root)
              (apply-assumption-to-root-type type assumption)
              type))
          (:type root)
          assumptions))

(s/defn assumption-base-type
  [assumption :- aos/RootedAssumption
   assumptions :- [aos/Assumption]]
  :- ats/SemanticType
  (let [same-proposition? #(same-assumption-proposition? % assumption)]
    (refine-root-type (:root assumption)
                      (remove same-proposition? assumptions))))

(s/defn ^:private type-predicate-classification
  [base :- ats/SemanticType
   pred-info :- aos/PredInfo]
  :- s/Any
  (let [pos (an/partition-type-for-predicate base pred-info true)
        neg (an/partition-type-for-predicate base pred-info false)]
    (cond
      (and (not (at/bottom-type? pos)) (at/bottom-type? neg)) :always
      (and (at/bottom-type? pos) (not (at/bottom-type? neg))) :never
      (sums/exhausted-by-types? base [pos]) :always
      (sums/exhausted-by-types? base [neg]) :never
      :else :unknown)))

(s/defn ^:private value-in-values-classification
  [base :- ats/SemanticType
   values :- [s/Any]]
  :- s/Any
  (let [pos (an/partition-type-for-values base values true)
        neg (an/partition-type-for-values base values false)]
    (cond
      (at/bottom-type? pos) :never
      (at/bottom-type? neg) :always
      :else :unknown)))

(s/defn ^:private value-not-in-values-classification
  [base :- ats/SemanticType
   values :- [s/Any]]
  :- s/Any
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

(s/defn assumption-truth
  [assumption :- aos/Assumption
   assumptions :- [aos/Assumption]]
  :- aos/AssumptionTruth
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

(s/defn origin-type
  [origin :- aos/Origin
   assumptions :- [aos/Assumption]]
  :- ats/SemanticType
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
    (at/semantic-type-value? entry)
    [(ato/normalize entry) (root-origin sym (ato/normalize entry))]

    (map? entry)
    (let [t (ato/normalize (or (:type entry) (aapi/dyn ctx)))
          origin (or (:origin entry) (root-origin sym t))]
      [t origin])

    :else
    (let [d (aapi/dyn ctx)]
      [d (root-origin sym d)])))

(s/defn effective-type
  [ctx :- s/Any
   sym :- s/Any
   entry :- s/Any
   assumptions :- [aos/Assumption]]
  :- ats/SemanticType
  (let [[t origin] (local-type-and-origin ctx sym entry)
        refined (or (some-> origin (origin-type assumptions)) t)]
    (ato/normalize refined)))

(s/defn local-root-origin
  [ctx :- s/Any
   node :- s/Any]
  :- (s/maybe aos/RootOrigin)
  (let [origin (node-origin node)]
    (cond
      (= :root (:kind origin))
      origin

      (aapi/local-node? node)
      (root-origin (aapi/node-form node) (or (aapi/node-type node) (aapi/dyn ctx)))

      :else nil)))

(s/defn contains-key-test-assumption
  [ctx :- s/Any
   target-node :- s/Any
   key :- s/Any]
  :- (s/maybe aos/Assumption)
  (when-let [root (local-root-origin ctx target-node)]
    {:kind :contains-key
     :root root
     :key key
     :polarity true}))

(defn- path-value-equality-assumption
  [target literal]
  (let [origin (aapi/node-origin target)]
    (when (= :map-key-lookup (:kind origin))
      {:kind :path-value-equality
       :root (:root origin)
       :path (:path origin)
       :values [(ac/literal-node-value literal)]
       :polarity true})))

(defn- equality-value-assumption
  [ctx left right]
  (let [[target literal] (cond
                           (and (aapi/stable-identity-node? left) (ac/literal-map-key? right)) [left right]
                           (and (aapi/stable-identity-node? right) (ac/literal-map-key? left)) [right left])]
    (when target
      (or (path-value-equality-assumption target literal)
          (when-let [root (local-root-origin ctx target)]
            {:kind :value-equality
             :root root
             :values [(ac/literal-node-value literal)]
             :polarity true})))))

(defn- boolean-proposition-assumption
  [test-node]
  (let [type (aapi/node-type test-node)]
    (when (and type
               (sums/exhausted-by-values? type [true false])
               (not (aapi/stable-identity-node? test-node)))
      {:kind :boolean-proposition
       :expr (aapi/node-form test-node)
       :polarity true})))

(defn- map-key-lookup-path-assumption
  [target pred-info polarity]
  (let [origin (node-origin target)]
    (when (= :map-key-lookup (:kind origin))
      (cond-> {:kind :path-type-predicate
               :root (:root origin)
               :path (:path origin)
               :pred (:pred pred-info)
               :polarity polarity}
        (:class pred-info) (assoc :class (:class pred-info))))))

(s/defn test->assumption
  [ctx :- s/Any
   test-node :- s/Any]
  :- (s/maybe aos/Assumption)
  (cond
    (aapi/stable-identity-node? test-node)
    (when-let [root (local-root-origin ctx test-node)]
      {:kind :truthy-local
       :root root
       :polarity true})

    (= :instance? (aapi/node-op test-node))
    (let [target (aapi/node-target test-node)
          cls (aapi/node-class test-node)]
      (when (class? cls)
        (cond
          (and (aapi/stable-identity-node? target) (local-root-origin ctx target))
          {:kind :type-predicate
           :root (local-root-origin ctx target)
           :pred :instance?
           :class cls
           :polarity true}

          :else
          (map-key-lookup-path-assumption target {:pred :instance? :class cls} true))))

    (and (= :invoke (aapi/node-op test-node))
         (ac/not-call? (aapi/call-fn-node test-node)))
    (let [args (aapi/call-args test-node)]
      (when (= 1 (count args))
        (some-> (first args)
                (->> (test->assumption ctx))
                invert-assumption)))

    (and (= :invoke (aapi/node-op test-node))
         (ac/equality-call? (aapi/call-fn-node test-node)))
    (let [[left right] (aapi/call-args test-node)]
      (equality-value-assumption ctx left right))

    (and (= :static-call (aapi/node-op test-node))
         (ac/static-equality-call? test-node))
    (let [[left right] (aapi/call-args test-node)]
      (equality-value-assumption ctx left right))

    (and (= :invoke (aapi/node-op test-node))
         (ac/type-predicate-call? (aapi/call-fn-node test-node) (aapi/call-args test-node)))
    (let [args (aapi/call-args test-node)
          info (ac/type-predicate-assumption-info (aapi/call-fn-node test-node) args)
          targ (if (= :instance? (:pred info))
                 (second args)
                 (first args))]
      (when info
        (cond
          (and (aapi/stable-identity-node? targ) (local-root-origin ctx targ))
          (cond-> {:kind :type-predicate
                   :root (local-root-origin ctx targ)
                   :pred (:pred info)
                   :polarity true}
            (:class info) (assoc :class (:class info)))

          :else
          (map-key-lookup-path-assumption targ info true))))

    (and (= :invoke (aapi/node-op test-node))
         (ac/blank-call? (aapi/call-fn-node test-node)))
    (let [targ (first (aapi/call-args test-node))]
      (when (and (aapi/stable-identity-node? targ) (local-root-origin ctx targ))
        {:kind :blank-check
         :root (local-root-origin ctx targ)
         :polarity true}))

    (ac/keyword-invoke-on-local? test-node)
    (when-let [[kw target] (ac/keyword-invoke-kw-and-target test-node)]
      (when (keyword? kw)
        (contains-key-test-assumption ctx target kw)))

    (and (= :invoke (aapi/node-op test-node))
         (ac/contains-call? (aapi/call-fn-node test-node)))
    (let [[target-node key-node] (aapi/call-args test-node)]
      (when (ac/literal-map-key? key-node)
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key)
            (contains-key-test-assumption ctx target-node key)))))

    (and (= :static-call (aapi/node-op test-node))
         (ac/static-contains-call? test-node))
    (let [[target-node key-node] (aapi/call-args test-node)]
      (when (ac/literal-map-key? key-node)
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key)
            (contains-key-test-assumption ctx target-node key)))))

    (and (= :static-call (aapi/node-op test-node))
         (ac/static-nil?-call? test-node))
    (when-let [targ (ac/static-nil?-target test-node)]
      (cond
        (and (aapi/stable-identity-node? targ) (local-root-origin ctx targ))
        {:kind :type-predicate
         :root (local-root-origin ctx targ)
         :pred :nil?
         :polarity true}

        :else
        (map-key-lookup-path-assumption targ {:pred :nil?} true)))

    (aapi/let-node? test-node)
    (test->assumption ctx (aapi/node-body test-node))

    (aapi/if-node? test-node)
    (test->assumption ctx (aapi/then-node test-node))

    :else
    (boolean-proposition-assumption test-node)))

(s/defn local-binding-init-assumption
  [ctx :- s/Any
   test-node :- s/Any
   locals :- s/Any]
  :- (s/maybe aos/Assumption)
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

(s/defn region-conjuncts
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
   :- aos/Conjuncts
   (region-conjuncts ctx node locals {}))
  ([ctx :- s/Any
    node :- s/Any
    locals :- s/Any
    alias-map :- s/Any]
   :- aos/Conjuncts
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

(s/defn if-test-conjuncts
  [ctx :- s/Any
   test-node :- s/Any
   locals :- s/Any]
  :- aos/Conjuncts
  (region-conjuncts ctx test-node locals))

(defn- refine-local-entry
  [ctx sym entry assumptions]
  (let [refined-type (effective-type ctx sym entry assumptions)]
    (if (at/semantic-type-value? entry)
      refined-type
      (assoc entry :type refined-type))))

(s/defn refine-locals-for-assumption
  [ctx :- s/Any
   locals :- s/Any
   assumptions :- [aos/Assumption]]
  :- s/Any
  (into {}
        (map (fn [[sym entry]]
               [sym (refine-local-entry ctx sym entry assumptions)]))
        locals))

(s/defn branch-local-envs
  [ctx :- s/Any
   locals :- s/Any
   assumptions :- [aos/Assumption]
   conjuncts :- aos/Conjuncts]
  :- aos/BranchEnvs
  (let [{:keys [then-conjuncts else-conjuncts]} conjuncts
        then-assumptions (into (vec assumptions) then-conjuncts)
        else-assumptions (into (vec assumptions) else-conjuncts)]
    {:then-locals (refine-locals-for-assumption ctx locals then-assumptions)
     :then-assumptions then-assumptions
     :else-locals (refine-locals-for-assumption ctx locals else-assumptions)
     :else-assumptions else-assumptions}))

(s/defn guard-assumption
  [stmt-node :- s/Any]
  :- (s/maybe aos/Assumption)
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

(s/defn apply-guard-assumption
  [ctx :- s/Any
   assumption :- (s/maybe aos/Assumption)]
  :- s/Any
  (let [{:keys [locals assumptions]} ctx
        parts (if (and assumption (= :conjunction (:kind assumption)))
                (:parts assumption)
                (if assumption [assumption] []))
        new-assumptions (into (vec assumptions) parts)]
    (assoc ctx
           :assumptions new-assumptions
           :locals (refine-locals-for-assumption ctx locals new-assumptions))))
