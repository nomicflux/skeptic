(ns skeptic.analysis.origin
  (:require [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]))


(defn root-origin
  [sym type]
  {:kind :root
   :sym sym
   :type (ato/normalize-for-declared-type type)})

(defn opaque-origin
  [type]
  {:kind :opaque
   :type (ato/normalize type)})

(defn type-origin
  [sym t]
  (root-origin sym t))

(defn node-origin
  [node]
  (or (aapi/node-origin node)
      (when-let [type (aapi/node-type node)]
        (opaque-origin type))))

(defn opposite-polarity
  [assumption]
  (case (:kind assumption)
    :conjunction nil
    (update assumption :polarity not)))

(defn- invertible-assumption?
  [assumption]
  (contains? #{:type-predicate
               :contains-key
               :blank-check
               :value-equality
               :conditional-branch}
             (:kind assumption)))

(defn- invert-assumption
  [assumption]
  (when (invertible-assumption? assumption)
    (opposite-polarity assumption)))

(defn same-assumption?
  [left right]
  (and (= (:kind left) (:kind right))
       (= (get-in left [:root :sym]) (get-in right [:root :sym]))
       (= (:polarity left) (:polarity right))
       (case (:kind left)
         :truthy-local true
         :blank-check true
         :contains-key (= (:key left) (:key right))
         :type-predicate (and (= (:pred left) (:pred right))
                              (= (:class left) (:class right)))
         :value-equality (= (:values left) (:values right))
         :conditional-branch (at/type-equal? (:narrowed-type left) (:narrowed-type right))
         :conjunction (and (= :conjunction (:kind right))
                           (= (count (:parts left)) (count (:parts right)))
                           (every? true? (map same-assumption? (:parts left) (:parts right))))
         false)))

(defn opposite-assumption?
  [left right]
  (same-assumption? left (opposite-polarity right)))

(defn same-assumption-proposition?
  "Same narrowed fact on the same root, ignoring branch polarity."
  [a b]
  (and (= (:kind a) (:kind b))
       (= (get-in a [:root :sym]) (get-in b [:root :sym]))
       (case (:kind a)
         :truthy-local true
         :blank-check true
         :contains-key (= (:key a) (:key b))
         :type-predicate (and (= (:pred a) (:pred b))
                              (= (:class a) (:class b)))
         :value-equality (= (:values a) (:values b))
         :conditional-branch (at/type-equal? (:narrowed-type a) (:narrowed-type b))
         :conjunction (and (= :conjunction (:kind b))
                           (= (count (:parts a)) (count (:parts b)))
                           (every? true? (map same-assumption-proposition? (:parts a) (:parts b))))
         false)))

(defn assumption-root?
  [assumption root]
  (= (get-in assumption [:root :sym]) (:sym root)))

(defn apply-assumption-to-root-type
  [type assumption]
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

      :conditional-branch
      (:narrowed-type assumption)

      type)))

(defn refine-root-type
  [root assumptions]
  (reduce (fn [type assumption]
            (if (assumption-root? assumption root)
              (apply-assumption-to-root-type type assumption)
              type))
          (:type root)
          assumptions))

(defn assumption-base-type
  [assumption assumptions]
  (let [same-proposition? #(same-assumption-proposition? % assumption)]
    (refine-root-type (:root assumption)
                      (remove same-proposition? assumptions))))

(defn- type-predicate-classification
  [base pred-info]
  (let [pos (an/partition-type-for-predicate base pred-info true)
        neg (an/partition-type-for-predicate base pred-info false)]
    (cond
      (and (not (at/bottom-type? pos)) (at/bottom-type? neg)) :always
      (and (at/bottom-type? pos) (not (at/bottom-type? neg))) :never
      :else :unknown)))

(defn- value-in-values-classification
  [base values]
  (let [pos (an/partition-type-for-values base values true)
        neg (an/partition-type-for-values base values false)]
    (cond
      (at/bottom-type? pos) :never
      (at/bottom-type? neg) :always
      :else :unknown)))

(defn- value-not-in-values-classification
  [base values]
  (let [pos (an/partition-type-for-values base values true)
        neg (an/partition-type-for-values base values false)]
    (cond
      (at/bottom-type? neg) :never
      (at/bottom-type? pos) :always
      :else :unknown)))

(defn assumption-truth
  [assumption assumptions]
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

      :unknown)))

(defn origin-type
  [origin assumptions]
  (case (:kind origin)
    :root (refine-root-type origin assumptions)
    :opaque (:type origin)
    :map-key-lookup
    (let [looked-up (amo/map-get-type (refine-root-type (:root origin) assumptions)
                                      (:key-query origin))]
      (if-some [binding-sym (:binding-sym origin)]
        (refine-root-type (root-origin binding-sym looked-up) assumptions)
        looked-up))
    :branch (case (assumption-truth (:test origin) assumptions)
              :true (origin-type (:then-origin origin) assumptions)
              :false (origin-type (:else-origin origin) assumptions)
              (let [then-t (origin-type (:then-origin origin) assumptions)
                    else-t (origin-type (:else-origin origin) assumptions)]
                (av/join (ato/derive-prov then-t else-t) [then-t else-t])))
    (:type origin)))

(defn- local-type-and-origin
  [ctx sym entry]
  (cond
    (at/semantic-type-value? entry)
    [(ato/normalize-for-declared-type entry) (root-origin sym (ato/normalize-for-declared-type entry))]

    (map? entry)
    (let [t (ato/normalize-for-declared-type (or (:type entry) (aapi/dyn ctx)))
          origin (or (:origin entry) (root-origin sym t))]
      [t origin])

    :else
    (let [d (aapi/dyn ctx)]
      [d (root-origin sym d)])))

(defn effective-type
  [ctx sym entry assumptions]
  (let [[t origin] (local-type-and-origin ctx sym entry)
        refined (or (some-> origin (origin-type assumptions)) t)]
    (ato/normalize-for-declared-type refined)))

(defn local-root-origin
  [ctx node]
  (let [origin (node-origin node)]
    (cond
      (= :root (:kind origin))
      origin

      (aapi/local-node? node)
      (root-origin (aapi/node-form node) (or (aapi/node-type node) (aapi/dyn ctx)))

      :else nil)))

(defn contains-key-test-assumption
  [ctx target-node key]
  (when-let [root (local-root-origin ctx target-node)]
    {:kind :contains-key
     :root root
     :key key
     :polarity true}))

(defn test->assumption
  [ctx test-node]
  (cond
    (aapi/local-node? test-node)
    (when-let [root (local-root-origin ctx test-node)]
      {:kind :truthy-local
       :root root
       :polarity true})

    (= :instance? (aapi/node-op test-node))
    (let [target (aapi/node-target test-node)
          cls (aapi/node-class test-node)]
      (when (and (aapi/local-node? target)
                 (class? cls)
                 (local-root-origin ctx target))
        {:kind :type-predicate
         :root (local-root-origin ctx target)
         :pred :instance?
         :class cls
         :polarity true}))

    (and (= :invoke (aapi/node-op test-node))
         (ac/not-call? (aapi/call-fn-node test-node)))
    (let [args (aapi/call-args test-node)]
      (when (= 1 (count args))
        (some-> (first args)
                (->> (test->assumption ctx))
                invert-assumption)))

    (and (= :invoke (aapi/node-op test-node))
         (ac/type-predicate-call? (aapi/call-fn-node test-node) (aapi/call-args test-node)))
    (let [args (aapi/call-args test-node)
          info (ac/type-predicate-assumption-info (aapi/call-fn-node test-node) args)
          targ (if (= :instance? (:pred info))
                 (second args)
                 (first args))]
      (when (and info (aapi/local-node? targ) (local-root-origin ctx targ))
        (cond-> {:kind :type-predicate
                 :root (local-root-origin ctx targ)
                 :pred (:pred info)
                 :polarity true}
          (:class info) (assoc :class (:class info)))))

    (and (= :invoke (aapi/node-op test-node))
         (ac/blank-call? (aapi/call-fn-node test-node)))
    (let [targ (first (aapi/call-args test-node))]
      (when (and (aapi/local-node? targ) (local-root-origin ctx targ))
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
      (when (and (aapi/local-node? targ) (local-root-origin ctx targ))
        {:kind :type-predicate
         :root (local-root-origin ctx targ)
         :pred :nil?
         :polarity true}))

    (aapi/let-node? test-node)
    (test->assumption ctx (aapi/node-body test-node))

    (aapi/if-node? test-node)
    (test->assumption ctx (aapi/then-node test-node))

    :else
    nil))

(defn local-binding-init-assumption
  [ctx test-node locals]
  (when (and (aapi/local-node? test-node) locals)
    (when-let [entry (get locals (aapi/node-form test-node))]
      (when-let [init (aapi/binding-init entry)]
        (test->assumption ctx init)))))

(defn and-chain-assumptions
  "Assumptions for `clojure.core/and` expansion: (let [g e1] (if g e2' g))."
  [ctx node]
  (if (and (aapi/let-node? node)
           (= 1 (count (aapi/node-bindings node))))
    (let [b (first (aapi/node-bindings node))
          sym (aapi/node-form b)
          init (aapi/node-init b)
          body (aapi/node-body node)]
      (if (and (aapi/if-node? body)
               (aapi/local-node? (aapi/node-test body))
               (= sym (aapi/node-form (aapi/node-test body))))
        (vec (concat (when-some [a (test->assumption ctx init)] [a])
                     (and-chain-assumptions ctx (aapi/then-node body))))
        (and-chain-assumptions ctx body)))
    (case (aapi/node-op node)
      :if (when-some [a (test->assumption ctx (aapi/node-test node))] [a])
      :let (and-chain-assumptions ctx (aapi/node-body node))
      :local []
      (when-some [a (test->assumption ctx node)] [a]))))

(defn if-test-conjuncts
  [ctx test-node locals]
  (let [chain (and-chain-assumptions ctx test-node)]
    (if (seq chain)
      chain
      (vec (concat (when-some [a (test->assumption ctx test-node)] [a])
                   (when-some [a (local-binding-init-assumption ctx test-node locals)] [a]))))))

(defn- refine-local-entry
  [ctx sym entry assumptions]
  (let [refined-type (effective-type ctx sym entry assumptions)]
    (if (at/semantic-type-value? entry)
      refined-type
      (assoc entry :type refined-type))))

(defn refine-locals-for-assumption
  [ctx locals assumptions]
  (into {}
        (map (fn [[sym entry]]
               [sym (refine-local-entry ctx sym entry assumptions)]))
        locals))

(defn branch-local-envs
  [ctx locals assumptions conjuncts]
  (let [conjuncts (vec conjuncts)
        then-assumptions (into (vec assumptions) conjuncts)
        else-assumptions (if (= 1 (count conjuncts))
                           (let [opp (opposite-polarity (first conjuncts))]
                             (cond-> (vec assumptions) opp (conj opp)))
                           (vec assumptions))]
    {:then-locals (refine-locals-for-assumption ctx locals then-assumptions)
     :then-assumptions then-assumptions
     :else-locals (refine-locals-for-assumption ctx locals else-assumptions)
     :else-assumptions else-assumptions}))

(defn guard-assumption
  [stmt-node]
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

(defn apply-guard-assumption
  [{:keys [locals assumptions] :as ctx} assumption]
  (let [parts (if (and assumption (= :conjunction (:kind assumption)))
                (:parts assumption)
                (if assumption [assumption] []))
        new-assumptions (into (vec assumptions) parts)]
    (assoc ctx
           :assumptions new-assumptions
           :locals (refine-locals-for-assumption ctx locals new-assumptions))))
