(ns skeptic.analysis.annotate.match
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]))

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
  [target]
  (or (ao/local-root-origin target)
      (when (= :local (:op target))
        (ao/root-origin (:form target) (or (:type target) at/Dyn)))))

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
      (case-get-access-kw-and-target node)))

(defn case-kw-root-info
  [test-node]
  (some (fn [node]
          (when-let [[kw target] (case-kw-and-target node)]
            (when-let [root (and (keyword? kw) (case-assumption-root-for-local target))]
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

(defn case-conditional-narrow-for-lits
  [branches kw lits]
  (let [pick (fn [lit]
               (some (fn [[pred branch-type]]
                       (when (case-predicate-matches-lit? pred kw lit) branch-type))
                     branches))
        picked (vec (distinct (keep pick lits)))]
    (if (empty? picked) at/BottomType (ato/union-type picked))))

(defn case-conditional-default-narrow
  [branches kw all-lits]
  (let [matched? (fn [[pred _]]
                   (some #(case-predicate-matches-lit? pred kw %) all-lits))
        default-types (into [] (comp (remove matched?) (map second)) branches)]
    (if (empty? default-types) at/BottomType (ato/union-type default-types))))

(defn annotate-case-one-then
  [ctx locals assumptions i tests thens disc-root use-conditional? cond-branches kw-root-info]
  (let [lits (vec (distinct (case-test-literals (nth tests i))))
        assumption (cond
                     (and use-conditional? disc-root (seq lits))
                     {:kind :conditional-branch
                      :root disc-root
                      :narrowed-type (case-conditional-narrow-for-lits
                                      cond-branches (:kw kw-root-info) lits)
                      :polarity true}

                     (and disc-root (seq lits))
                     {:kind :value-equality
                      :root disc-root
                      :values lits
                      :polarity true}
                     :else nil)
        envs (ao/branch-local-envs locals assumptions (if assumption [assumption] []))
        then-body (:then (nth thens i))
        annotated ((:recurse ctx)
                   (assoc ctx
                          :locals (:then-locals envs)
                          :assumptions (:then-assumptions envs))
                   then-body)]
    (assoc (nth thens i) :then annotated)))

(defn- default-assumption
  [use-conditional? disc-root cond-branches kw-root-info all-values]
  (cond
    (and use-conditional? disc-root (seq all-values))
    {:kind :conditional-branch
     :root disc-root
     :narrowed-type (case-conditional-default-narrow
                     cond-branches (:kw kw-root-info) all-values)
     :polarity true}

    (and disc-root (seq all-values))
    {:kind :value-equality
     :root disc-root
     :values all-values
     :polarity false}
    :else nil))

(defn annotate-case
  [{:keys [locals assumptions] :as ctx} node]
  (let [test-node ((:recurse ctx) ctx (:test node))
        discriminant-expr (case-discriminant-expr-node test-node)
        tests (:tests node)
        thens (:thens node)
        n (min (count tests) (count thens))
        kw-root-info (case-kw-root-info discriminant-expr)
        disc-root (or (:root kw-root-info)
                      (some-> discriminant-expr
                              case-discriminant-leaf-node
                              case-assumption-root-for-local))
        cond-branches (when kw-root-info
                        (case-conditional-branches-from-type (:type (:root kw-root-info))))
        use-conditional? (seq cond-branches)
        all-values (into [] (distinct (mapcat case-test-literals (take n tests))))
        annotated-thens (mapv (fn [i]
                                (annotate-case-one-then
                                 ctx locals assumptions i tests thens
                                 disc-root use-conditional? cond-branches kw-root-info))
                              (range n))
        assumption (default-assumption use-conditional? disc-root cond-branches kw-root-info all-values)
        envs (ao/branch-local-envs locals assumptions (if assumption [assumption] []))
        default-node ((:recurse ctx)
                      (assoc ctx
                             :locals (:then-locals envs)
                             :assumptions (:then-assumptions envs))
                      (:default node))
        branch-types (mapv (comp :type :then) annotated-thens)
        joined (av/type-join* (conj branch-types (:type default-node)))]
    (assoc node
           :test test-node
           :tests (vec (take n tests))
           :thens annotated-thens
           :default default-node
           :type joined
           :origin (ao/opaque-origin joined))))
