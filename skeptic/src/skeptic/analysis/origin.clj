(ns skeptic.analysis.origin
  (:require [skeptic.analysis.calls :as ac]
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
    {:type (ato/normalize-type entry)}

    (and (map? entry)
         (or (contains? entry :type)
             (contains? entry :output-type)
             (contains? entry :arglists)))
    (cond-> (merge (dissoc entry :type :output-type :arglists)
                   {:type (ato/normalize-type (or (:type entry) at/Dyn))})
      (contains? entry :output-type)
      (assoc :output-type (ato/normalize-type (:output-type entry)))

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
   :type (ato/normalize-type type)})

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
  (or (:origin node)
      (when-let [type (:type node)]
        (opaque-origin type))))

(defn opposite-polarity
  [assumption]
  (update assumption :polarity not))

(defn same-assumption?
  [left right]
  (and (= (:kind left) (:kind right))
       (= (get-in left [:root :sym]) (get-in right [:root :sym]))
       (= (:polarity left) (:polarity right))
       (case (:kind left)
         :truthy-local true
         :contains-key (= (:key left) (:key right))
         :type-predicate (and (= (:pred left) (:pred right))
                              (= (:class left) (:class right)))
         :value-equality (= (:values left) (:values right))
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
         :contains-key (= (:key a) (:key b))
         :type-predicate (and (= (:pred a) (:pred b))
                              (= (:class a) (:class b)))
         :value-equality (= (:values a) (:values b))
         false)))

(defn assumption-root?
  [assumption root]
  (= (get-in assumption [:root :sym]) (:sym root)))

(defn apply-assumption-to-root-type
  [type assumption]
  (case (:kind assumption)
    :truthy-local
    (an/apply-truthy-local type (:polarity assumption))

    :contains-key
    (avc/refine-type-by-contains-key type (:key assumption) (:polarity assumption))

    :type-predicate
    (an/partition-type-for-predicate type
                                     {:pred (:pred assumption)
                                      :class (:class assumption)}
                                     (:polarity assumption))

    :value-equality
    (an/partition-type-for-values type (:values assumption) (:polarity assumption))

    type))

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

      :truthy-local
      :unknown

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
      true (assoc :type (ato/normalize-type type))
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
    (= :local (:op test-node))
    (when-let [root (local-root-origin test-node)]
      {:kind :truthy-local
       :root root
       :polarity true})

    (= :instance? (:op test-node))
    (let [target (:target test-node)
          cls (:class test-node)]
      (when (and (= :local (:op target))
                 (class? cls)
                 (local-root-origin target))
        {:kind :type-predicate
         :root (local-root-origin target)
         :pred :instance?
         :class cls
         :polarity true}))

    (and (= :invoke (:op test-node))
         (ac/type-predicate-call? (:fn test-node) (:args test-node)))
    (let [args (:args test-node)
          info (ac/type-predicate-assumption-info (:fn test-node) args)
          targ (if (= :instance? (:pred info))
                 (second args)
                 (first args))]
      (when (and info (= :local (:op targ)) (local-root-origin targ))
        (cond-> {:kind :type-predicate
                 :root (local-root-origin targ)
                 :pred (:pred info)
                 :polarity true}
          (:class info) (assoc :class (:class info)))))

    (and (= :invoke (:op test-node))
         (ac/keyword-invoke-on-local? test-node))
    (let [kw (ac/literal-node-value (:fn test-node))
          target (first (:args test-node))]
      (when (keyword? kw)
        (contains-key-test-assumption target kw)))

    (and (= :invoke (:op test-node))
         (ac/contains-call? (:fn test-node)))
    (let [[target-node key-node] (:args test-node)]
      (when (ac/literal-map-key? key-node)
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key)
            (contains-key-test-assumption target-node key)))))

    (and (= :static-call (:op test-node))
         (ac/static-contains-call? test-node))
    (let [[target-node key-node] (:args test-node)]
      (when (ac/literal-map-key? key-node)
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key)
            (contains-key-test-assumption target-node key)))))

    (and (= :static-call (:op test-node))
         (ac/static-nil?-call? test-node))
    (when-let [targ (ac/static-nil?-target test-node)]
      (when (and (= :local (:op targ)) (local-root-origin targ))
        {:kind :type-predicate
         :root (local-root-origin targ)
         :pred :nil?
         :polarity true}))

    :else
    nil))

(defn refine-locals-for-assumption
  [locals assumptions]
  (into {}
        (map (fn [[sym entry]]
               [sym (effective-entry sym entry assumptions)]))
        locals))

(defn branch-local-envs
  [locals assumptions assumption]
  (let [then-assumptions (cond-> (vec assumptions)
                          assumption (conj assumption))
        else-assumption (some-> assumption opposite-polarity)
        else-assumptions (cond-> (vec assumptions)
                          else-assumption (conj else-assumption))]
    {:then-locals (refine-locals-for-assumption locals then-assumptions)
     :then-assumptions then-assumptions
     :else-locals (refine-locals-for-assumption locals else-assumptions)
     :else-assumptions else-assumptions}))
