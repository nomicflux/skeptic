(ns skeptic.analysis.origin
  (:require [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.narrowing :as an]
            [skeptic.analysis.value-check :as avc]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]))

(defn typed-entry
  [entry]
  (cond
    (nil? entry) nil

    (at/semantic-type-value? entry)
    {:type (ato/normalize-type-for-declared-type entry)}

    (and (map? entry)
         (or (contains? entry :type)
             (contains? entry :output-type)
             (contains? entry :arglists)))
    (cond-> (merge (dissoc entry :type :output-type :arglists)
                   {:type (ato/normalize-type-for-declared-type (or (:type entry) at/Dyn))})
      (contains? entry :output-type)
      (assoc :output-type (ato/normalize-type-for-declared-type (:output-type entry)))

      (contains? entry :arglists)
      (assoc :arglists (:arglists entry)))

    (map? entry)
    (throw (IllegalArgumentException.
            (format "Expected typed entry, got %s" (pr-str entry))))

    :else
    (throw (IllegalArgumentException.
            (format "Expected type entry, got %s" (pr-str entry))))))

(defn root-origin
  [sym type]
  {:kind :root
   :sym sym
   :type (ato/normalize-type-for-declared-type type)})

(defn opaque-origin
  [type]
  {:kind :opaque
   :type (ato/normalize-type type)})

(defn entry-origin
  [sym entry]
  (or (:origin entry)
      (when-let [type (:type entry)]
        (root-origin sym type))))

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
    :branch (case (assumption-truth (:test origin) assumptions)
              :true (origin-type (:then-origin origin) assumptions)
              :false (origin-type (:else-origin origin) assumptions)
              (av/type-join* [(origin-type (:then-origin origin) assumptions)
                              (origin-type (:else-origin origin) assumptions)]))
    (:type origin)))

(defn effective-entry
  [sym entry assumptions]
  (let [entry (typed-entry entry)
        origin (entry-origin sym entry)
        type (or (some-> origin (origin-type assumptions))
                 (:type entry)
                 at/Dyn)]
    (cond-> (or entry {:type at/Dyn})
      true (assoc :type (ato/normalize-type-for-declared-type type))
      origin (assoc :origin origin))))

(defn local-root-origin
  [node]
  (let [origin (node-origin node)]
    (when (= :root (:kind origin))
      origin)))

(defn contains-key-test-assumption
  [target-node key]
  (when-let [root (local-root-origin target-node)]
    {:kind :contains-key
     :root root
     :key key
     :polarity true}))

(defn test->assumption
  [test-node]
  (cond
    (aapi/local-node? test-node)
    (when-let [root (local-root-origin test-node)]
      {:kind :truthy-local
       :root root
       :polarity true})

    (= :instance? (aapi/node-op test-node))
    (let [target (aapi/node-target test-node)
          cls (aapi/node-class test-node)]
      (when (and (aapi/local-node? target)
                 (class? cls)
                 (local-root-origin target))
        {:kind :type-predicate
         :root (local-root-origin target)
         :pred :instance?
         :class cls
         :polarity true}))

    (and (= :invoke (aapi/node-op test-node))
         (ac/type-predicate-call? (aapi/call-fn-node test-node) (aapi/call-args test-node)))
    (let [args (aapi/call-args test-node)
          info (ac/type-predicate-assumption-info (aapi/call-fn-node test-node) args)
          targ (if (= :instance? (:pred info))
                 (second args)
                 (first args))]
      (when (and info (aapi/local-node? targ) (local-root-origin targ))
        (cond-> {:kind :type-predicate
                 :root (local-root-origin targ)
                 :pred (:pred info)
                 :polarity true}
          (:class info) (assoc :class (:class info)))))

    (and (= :invoke (aapi/node-op test-node))
         (ac/blank-call? (aapi/call-fn-node test-node)))
    (let [targ (first (aapi/call-args test-node))]
      (when (and (aapi/local-node? targ) (local-root-origin targ))
        {:kind :blank-check
         :root (local-root-origin targ)
         :polarity true}))

    (ac/keyword-invoke-on-local? test-node)
    (when-let [[kw target] (ac/keyword-invoke-kw-and-target test-node)]
      (when (keyword? kw)
        (contains-key-test-assumption target kw)))

    (and (= :invoke (aapi/node-op test-node))
         (ac/contains-call? (aapi/call-fn-node test-node)))
    (let [[target-node key-node] (aapi/call-args test-node)]
      (when (ac/literal-map-key? key-node)
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key)
            (contains-key-test-assumption target-node key)))))

    (and (= :static-call (aapi/node-op test-node))
         (ac/static-contains-call? test-node))
    (let [[target-node key-node] (aapi/call-args test-node)]
      (when (ac/literal-map-key? key-node)
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key)
            (contains-key-test-assumption target-node key)))))

    (and (= :static-call (aapi/node-op test-node))
         (ac/static-nil?-call? test-node))
    (when-let [targ (ac/static-nil?-target test-node)]
      (when (and (aapi/local-node? targ) (local-root-origin targ))
        {:kind :type-predicate
         :root (local-root-origin targ)
         :pred :nil?
         :polarity true}))

    (aapi/let-node? test-node)
    (test->assumption (aapi/node-body test-node))

    (aapi/if-node? test-node)
    (test->assumption (aapi/then-node test-node))

    :else
    nil))

(defn local-binding-init-assumption
  [test-node locals]
  (when (and (aapi/local-node? test-node) locals)
    (when-let [entry (get locals (aapi/node-form test-node))]
      (when-let [init (aapi/binding-init entry)]
        (test->assumption init)))))

(defn and-chain-assumptions
  "Assumptions for `clojure.core/and` expansion: (let [g e1] (if g e2' g))."
  [node]
  (if (and (aapi/let-node? node)
           (= 1 (count (aapi/node-bindings node))))
    (let [b (first (aapi/node-bindings node))
          sym (aapi/node-form b)
          init (aapi/node-init b)
          body (aapi/node-body node)]
      (if (and (aapi/if-node? body)
               (aapi/local-node? (aapi/node-test body))
               (= sym (aapi/node-form (aapi/node-test body))))
        (vec (concat (when-some [a (test->assumption init)] [a])
                     (and-chain-assumptions (aapi/then-node body))))
        (and-chain-assumptions body)))
    (case (aapi/node-op node)
      :if (when-some [a (test->assumption (aapi/node-test node))] [a])
      :let (and-chain-assumptions (aapi/node-body node))
      :local []
      (when-some [a (test->assumption node)] [a]))))

(defn if-test-conjuncts
  [test-node locals]
  (let [chain (and-chain-assumptions test-node)]
    (if (seq chain)
      chain
      (or (when-some [a (local-binding-init-assumption test-node locals)] [a])
          (when-some [a (test->assumption test-node)] [a])
          []))))

(defn refine-locals-for-assumption
  [locals assumptions]
  (into {}
        (map (fn [[sym entry]]
               [sym (effective-entry sym entry assumptions)]))
        locals))

(defn branch-local-envs
  [locals assumptions conjuncts]
  (let [conjuncts (vec conjuncts)
        then-assumptions (into (vec assumptions) conjuncts)
        else-assumptions (if (= 1 (count conjuncts))
                           (let [opp (opposite-polarity (first conjuncts))]
                             (cond-> (vec assumptions) opp (conj opp)))
                           (vec assumptions))]
    {:then-locals (refine-locals-for-assumption locals then-assumptions)
     :then-assumptions then-assumptions
     :else-locals (refine-locals-for-assumption locals else-assumptions)
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
           :locals (refine-locals-for-assumption locals new-assumptions))))
