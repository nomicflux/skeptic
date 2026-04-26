(ns skeptic.analysis.annotate.match
  (:require [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.sum-types :as sums]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(defn case-test-literal-nodes
  [case-test-entry]
  (when case-test-entry
    (let [candidates (cond
                       (#{:const :quote} (:op case-test-entry))
                       [case-test-entry]

                       (= :case-test (:op case-test-entry))
                       [(:test case-test-entry)]

                       :else
                       (let [raw (or (:tests case-test-entry) (:test case-test-entry))]
                         (when raw (if (vector? raw) raw [raw]))))]
      (when candidates
        (vec (filter #(#{:const :quote} (:op %)) candidates))))))

(defn case-test-literals
  [case-test-node]
  (mapv ac/literal-node-value (case-test-literal-nodes case-test-node)))

(defn case-discriminant-expr-node
  [test-node]
  (if (and (= :local (:op test-node)) (:binding-init test-node))
    (:binding-init test-node)
    test-node))

(defn case-discriminant-leaf-node
  [node]
  (case (:op node)
    :do (case-discriminant-leaf-node (:ret node))
    :let (case-discriminant-leaf-node (:body node))
    node))

(defn case-assumption-root-for-local
  [ctx target]
  (or (ao/local-root-origin ctx target)
      (when (= :local (:op target))
        (ao/root-origin (:form target) (or (:type target) (aapi/dyn ctx))))))

(defn- case-get-access-kw-and-target
  [node]
  (cond
    (and (= :invoke (:op node)) (ac/get-call? (:fn node)))
    (let [[target key-node] (:args node)]
      (when (and (= :local (:op target)) (ac/literal-map-key? key-node))
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key) [key target]))))

    (and (= :static-call (:op node)) (ac/static-get-call? node))
    (let [[target key-node] (:args node)]
      (when (and (= :local (:op target)) (ac/literal-map-key? key-node))
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key) [key target]))))
    :else nil))

(defn case-kw-and-target
  [node]
  (or (ac/keyword-invoke-kw-and-target node)
      (when (and (contains? aapi/invoke-ops (:op node))
                 (= 1 (count (:args node))))
        (let [summary (get-in node [:fn :accessor-summary])
              target (first (:args node))]
          (when (and (= :unary-map-accessor (:kind summary))
                     (= :local (:op target)))
            [(:kw summary) target])))
      (case-get-access-kw-and-target node)))

(defn case-kw-root-info
  [ctx test-node]
  (some (fn [node]
          (when-let [[kw target] (case-kw-and-target node)]
            (when-let [root (and (keyword? kw) (case-assumption-root-for-local ctx target))]
              {:kw kw :root root})))
        (sac/ast-nodes test-node)))

(defn case-predicate-test-map
  [kw lit]
  (cond-> {kw lit}
    (keyword? lit) (assoc lit true)))

(defn case-predicate-matches-lit?
  [pred kw lit]
  (boolean
   (try
     (pred (case-predicate-test-map kw lit))
     (catch Exception _ false))))

(defn case-conditional-branches-from-type
  [type]
  (let [type (if (at/maybe-type? type) (:inner type) type)]
    (cond
      (at/conditional-type? type) (:branches type)
      (at/union-type? type)
      (let [conditionals (filterv at/conditional-type? (:members type))]
        (when (= 1 (count conditionals))
          (:branches (first conditionals))))
      :else nil)))

(defn- discriminator-entry?
  [entry-key kw]
  (let [entry-key (if (at/optional-key-type? entry-key)
                    (:inner entry-key)
                    entry-key)]
    (and (at/value-type? entry-key)
         (= kw (:value entry-key)))))

(defn- drop-discriminator-key
  [type kw]
  (cond
    (at/map-type? type)
    (let [kept (into {}
                     (remove (fn [[entry-key _]]
                               (discriminator-entry? entry-key kw)))
                     (:entries type))
          refs (into [] (mapcat (fn [[k v]] [(prov/of k) (prov/of v)])) kept)]
      (at/->MapT (prov/with-refs (ato/derive-prov type) refs) kept))

    (at/union-type? type)
    (ato/union (map #(drop-discriminator-key % kw) (:members type)))

    (at/maybe-type? type)
    (at/->MaybeT (ato/derive-prov type) (drop-discriminator-key (:inner type) kw))

    :else type))

(defn narrow-conditional-by-discriminator
  "Pick branches of `branches` whose pred matches each literal in `lits`
   against discriminator key `kw`. Returns a union of selected branch
   types. With opts {:drop-discriminator? true}, drops the discriminator
   key from each picked branch."
  [anchor-prov branches kw lits {:keys [drop-discriminator?]}]
  (let [pick (fn [lit]
               (some (fn [[pred branch-type]]
                       (when (case-predicate-matches-lit? pred kw lit)
                         (if drop-discriminator?
                           (drop-discriminator-key branch-type kw)
                           branch-type)))
                     branches))
        picked (vec (distinct (keep pick lits)))]
    (if (empty? picked) (at/BottomType anchor-prov) (ato/union picked))))

(defn narrow-conditional-default
  "Default-branch counterpart: returns the union of branch types whose
   preds did NOT match any of `lits`. With {:drop-discriminator? true},
   drops the discriminator key from each picked branch."
  [anchor-prov branches kw lits {:keys [drop-discriminator?]}]
  (let [matched? (fn [[pred _]]
                   (some #(case-predicate-matches-lit? pred kw %) lits))
        default-types (into [] (comp (remove matched?)
                                     (map second)
                                     (map #(if drop-discriminator?
                                             (drop-discriminator-key % kw)
                                             %)))
                            branches)]
    (if (empty? default-types)
      (at/BottomType anchor-prov)
      (ato/union default-types))))

(defn case-conditional-narrow-for-lits
  [anchor-prov branches kw lits]
  (narrow-conditional-by-discriminator anchor-prov branches kw lits {:drop-discriminator? true}))

(defn case-conditional-default-narrow
  [anchor-prov branches kw all-lits]
  (narrow-conditional-default anchor-prov branches kw all-lits {:drop-discriminator? true}))

(defn annotate-case-one-then
  [anchor-prov ctx locals assumptions i tests thens disc-root use-conditional? cond-branches kw-root-info]
  (let [lits (vec (distinct (case-test-literals (nth tests i))))
        assumption (cond
                     (and use-conditional? disc-root (seq lits))
                     {:kind :conditional-branch
                      :root disc-root
                      :narrowed-type (case-conditional-narrow-for-lits
                                      anchor-prov cond-branches (:kw kw-root-info) lits)
                      :polarity true}

                     (and disc-root (seq lits))
                     {:kind :value-equality
                      :root disc-root
                      :values lits
                      :polarity true}
                     :else nil)
        envs (ao/branch-local-envs ctx locals assumptions
                                    (if assumption
                                      {:then-conjuncts [assumption] :else-conjuncts []}
                                      {:then-conjuncts [] :else-conjuncts []}))
        then-body (:then (nth thens i))
        annotated ((:recurse ctx)
                   (assoc ctx
                          :locals (:then-locals envs)
                          :assumptions (:then-assumptions envs))
                   then-body)]
    (assoc (nth thens i) :then annotated)))

(defn- default-assumption
  [anchor-prov use-conditional? disc-root cond-branches kw-root-info all-values]
  (cond
    (and use-conditional? disc-root (seq all-values))
    {:kind :conditional-branch
     :root disc-root
     :narrowed-type (case-conditional-default-narrow
                     anchor-prov cond-branches (:kw kw-root-info) all-values)
     :polarity true}

    (and disc-root (seq all-values))
    {:kind :value-equality
     :root disc-root
     :values all-values
     :polarity false}
    :else nil))

(defn- exhaustive-values?
  [disc-root values]
  (and disc-root
       (seq values)
       (sums/exhausted-by-values? (:type disc-root) values)))

(defn- case-joined-type
  [anchor-prov branch-types default-node exhaustive?]
  (av/type-join* anchor-prov
                 (if exhaustive?
                   branch-types
                   (conj branch-types (:type default-node)))))

(defn annotate-case
  [{:keys [locals assumptions] :as ctx} node]
  (let [anchor-prov (prov/with-ctx ctx)
        test-node ((:recurse ctx) ctx (:test node))
        discriminant-expr (case-discriminant-expr-node test-node)
        tests (:tests node)
        thens (:thens node)
        n (min (count tests) (count thens))
        kw-root-info (case-kw-root-info ctx discriminant-expr)
        disc-root (or (:root kw-root-info)
                      (when-let [leaf (some-> discriminant-expr case-discriminant-leaf-node)]
                        (case-assumption-root-for-local ctx leaf)))
        cond-branches (when kw-root-info
                        (case-conditional-branches-from-type (:type (:root kw-root-info))))
        use-conditional? (seq cond-branches)
        all-values (into [] (distinct (mapcat case-test-literals (take n tests))))
        annotated-thens (mapv (fn [i]
                                (annotate-case-one-then
                                 anchor-prov ctx locals assumptions i tests thens
                                 disc-root use-conditional? cond-branches kw-root-info))
                              (range n))
        assumption (default-assumption anchor-prov use-conditional? disc-root cond-branches kw-root-info all-values)
        envs (ao/branch-local-envs ctx locals assumptions
                                    (if assumption
                                      {:then-conjuncts [assumption] :else-conjuncts []}
                                      {:then-conjuncts [] :else-conjuncts []}))
        default-node ((:recurse ctx)
                      (assoc ctx
                             :locals (:then-locals envs)
                             :assumptions (:then-assumptions envs))
                      (:default node))
        branch-types (mapv (comp :type :then) annotated-thens)
        exhaustive? (exhaustive-values? disc-root all-values)
        joined (case-joined-type (prov/with-ctx ctx) branch-types default-node exhaustive?)]
    (assoc node
           :test test-node
           :tests (vec (take n tests))
           :thens annotated-thens
           :default default-node
           :type joined
           :origin (ao/opaque-origin joined))))
