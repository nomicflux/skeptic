(ns skeptic.analysis.annotate.match
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]))

(defn case-test-literal-nodes
  "Each `(:tests case-node)` entry is usually `:case-test` → `:const`; may be `:const` directly."
  [case-test-entry]
  (when case-test-entry
    (let [candidates (cond
                        (#{:const :quote} (:op case-test-entry))
                        [case-test-entry]
                        (= :case-test (:op case-test-entry))
                        [(:test case-test-entry)]
                        :else
                        (let [raw (or (:tests case-test-entry)
                                      (when-let [t (:test case-test-entry)]
                                        t))]
                          (when raw
                            (if (vector? raw) raw [raw]))))]
      (when candidates
        (vec (filter #(#{:const :quote} (:op %)) candidates))))))

(defn case-test-literals
  [case-test-node]
  (mapv ac/literal-node-value (case-test-literal-nodes case-test-node)))

(defn case-discriminant-expr-node
  "Case :test is often a synthetic local (G__) whose :binding-init is the real discriminant.
  ast/children does not walk :binding-init, so keyword-on-local must use this node."
  [test-node]
  (if (and (= :local (:op test-node)) (:binding-init test-node))
    (:binding-init test-node)
    test-node))

(defn case-discriminant-leaf-node
  "tools.analyzer may wrap the case test in :do / :let."
  [node]
  (case (:op node)
    :do (case-discriminant-leaf-node (:ret node))
    :let (case-discriminant-leaf-node (:body node))
    node))

(defn case-assumption-root-for-local
  "Like local-root-origin, but fall back to a fresh :root when the local only has an opaque origin."
  [targ]
  (or (ao/local-root-origin targ)
      (when (= :local (:op targ))
        (ao/root-origin (:form targ) (or (:type targ) at/Dyn)))))

(defn- case-get-access-kw-and-target
  [n]
  (cond
    (and (= :invoke (:op n))
         (ac/get-call? (:fn n)))
    (let [[target key-node] (:args n)]
      (when (and (= :local (:op target))
                 (ac/literal-map-key? key-node))
        (let [k (ac/literal-node-value key-node)]
          (when (keyword? k)
            [k target]))))

    (and (= :static-call (:op n))
         (ac/static-get-call? n))
    (let [[target key-node] (:args n)]
      (when (and (= :local (:op target))
                 (ac/literal-map-key? key-node))
        (let [k (ac/literal-node-value key-node)]
          (when (keyword? k)
            [k target]))))

    :else nil))

(defn- case-kw-and-target
  [n]
  (or (ac/keyword-invoke-kw-and-target n)
      (case-get-access-kw-and-target n)))

(defn case-kw-root-info
  "Case tests may wrap the discriminant (e.g. hashing); scan the full test subtree."
  [test-node]
  (some (fn [n]
          (when-let [[kw targ] (case-kw-and-target n)]
            (when-let [root (and (keyword? kw) (case-assumption-root-for-local targ))]
              {:kw kw :root root})))
        (sac/ast-nodes test-node)))

(defn case-predicate-test-map
  "Map used to evaluate s/conditional branch preds for a case arm literal.
  Includes `{kw lit}` plus `{lit true}` when lit is a keyword so preds such as
  `(contains? % :a)` align with case on `(:route x)` with literal `:a`."
  [kw lit]
  (cond-> {kw lit}
    (keyword? lit) (assoc lit true)))

(defn case-predicate-matches-lit?
  [pred kw lit]
  (boolean (try (pred (case-predicate-test-map kw lit))
                (catch Exception _ false))))

(defn case-conditional-branches-from-type
  [t]
  (let [t (if (at/maybe-type? t) (:inner t) t)]
    (cond
      (at/conditional-type? t) (:branches t)
      (at/union-type? t)
      (let [cs (filterv at/conditional-type? (:members t))]
        (when (= 1 (count cs))
          (:branches (first cs))))
      :else nil)))

(defn case-conditional-narrow-for-lits
  "Uses first matching branch per literal, matching Plumatic s/conditional dispatch."
  [branches kw lits]
  (let [pick (fn [lit]
               (let [m (case-predicate-test-map kw lit)]
                 (some (fn [[pred btyp]]
                         (when (boolean (try (pred m) (catch Exception _ false)))
                           btyp))
                       branches)))
        picked (vec (distinct (keep pick lits)))]
    (if (empty? picked)
      at/BottomType
      (ato/union-type picked))))

(defn case-conditional-default-narrow
  [branches kw all-lits]
  (let [branch-matched? (fn [[pred _]]
                          (some #(case-predicate-matches-lit? pred kw %) all-lits))
        default-types (into [] (comp (remove branch-matched?) (map second)) branches)]
    (if (empty? default-types)
      at/BottomType
      (ato/union-type default-types))))

(defn- annotate-case-one-then
  [ctx locals assumptions i tests thens disc-root use-conditional? cond-branches kw-root-info]
  (let [lits (vec (distinct (case-test-literals (nth tests i))))
        assumption (cond
                     (and use-conditional? disc-root (seq lits))
                     {:kind :conditional-branch
                      :root disc-root
                      :narrowed-type (case-conditional-narrow-for-lits
                                      cond-branches
                                      (:kw kw-root-info)
                                      lits)
                      :polarity true}

                     (and disc-root (seq lits))
                     {:kind :value-equality
                      :root disc-root
                      :values lits
                      :polarity true}

                     :else nil)
        {:keys [then-locals then-assumptions]}
        (ao/branch-local-envs locals assumptions (if assumption [assumption] []))
        then-body (:then (nth thens i))
        ann ((:recurse ctx) (assoc ctx
                                   :locals then-locals
                                   :assumptions then-assumptions)
             then-body)]
    (assoc (nth thens i) :then ann)))

(defn annotate-case
  [{:keys [locals assumptions] :as ctx} node]
  (let [test-node ((:recurse ctx) ctx (:test node))
        discriminant-expr (case-discriminant-expr-node test-node)
        tests (:tests node)
        thens (:thens node)
        default (:default node)
        n (min (count tests) (count thens))
        kw-root-info (case-kw-root-info discriminant-expr)
        disc-root (or (:root kw-root-info)
                      (some-> discriminant-expr case-discriminant-leaf-node case-assumption-root-for-local))
        cond-branches (when kw-root-info
                        (case-conditional-branches-from-type (:type (:root kw-root-info))))
        use-conditional? (seq cond-branches)
        all-values (into [] (distinct (mapcat case-test-literals (take n tests))))
        annotated-thens
        (mapv (fn [i]
                (annotate-case-one-then ctx locals assumptions i tests thens
                                        disc-root use-conditional? cond-branches kw-root-info))
              (range n))
        default-assumption (cond
                             (and use-conditional? disc-root (seq all-values))
                             {:kind :conditional-branch
                              :root disc-root
                              :narrowed-type (case-conditional-default-narrow
                                              cond-branches
                                              (:kw kw-root-info)
                                              all-values)
                              :polarity true}

                             (and disc-root (seq all-values))
                             {:kind :value-equality
                              :root disc-root
                              :values all-values
                              :polarity false}

                             :else nil)
        {:keys [then-locals then-assumptions]}
        (ao/branch-local-envs locals assumptions (if default-assumption [default-assumption] []))
        default-node ((:recurse ctx) (assoc ctx
                                            :locals then-locals
                                            :assumptions then-assumptions)
                      default)
        branch-types (mapv (comp :type :then) annotated-thens)
        joined (av/type-join* (conj branch-types (:type default-node)))]
    (assoc node
           :test test-node
           :tests (vec (take n tests))
           :thens annotated-thens
           :default default-node
           :type joined
           :origin (ao/opaque-origin joined))))
