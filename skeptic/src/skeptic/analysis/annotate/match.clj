(ns skeptic.analysis.annotate.match
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.sum-types :as sums]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]))

(s/defn case-test-literal-nodes
  [case-test-entry :- s/Any]
  :- (s/maybe [s/Any])
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

(s/defn case-test-literals
  [case-test-node :- s/Any]
  :- [s/Any]
  (mapv ac/literal-node-value (case-test-literal-nodes case-test-node)))

(s/defn case-discriminant-expr-node
  [test-node :- s/Any]
  :- s/Any
  (if (and (= :local (:op test-node)) (:binding-init test-node))
    (:binding-init test-node)
    test-node))

(s/defn case-discriminant-leaf-node
  [node :- s/Any]
  :- s/Any
  (case (:op node)
    :do (case-discriminant-leaf-node (:ret node))
    :let (case-discriminant-leaf-node (:body node))
    node))

(s/defn case-assumption-root-for-local
  [ctx :- s/Any, target :- s/Any]
  :- (s/maybe aos/RootOrigin)
  (or (ao/local-root-origin ctx target)
      (when (= :local (:op target))
        (ao/root-origin (:form target) (or (:type target) (aapi/dyn ctx))))))

(defn- case-get-access-kw-and-target
  [node]
  (cond
    (and (= :invoke (:op node)) (ac/get-call? (:fn node)))
    (let [[target key-node] (:args node)]
      (when (and (aapi/stable-identity-node? target) (ac/literal-map-key? key-node))
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key) [key target]))))

    (and (= :static-call (:op node)) (ac/static-get-call? node))
    (let [[target key-node] (:args node)]
      (when (and (aapi/stable-identity-node? target) (ac/literal-map-key? key-node))
        (let [key (ac/literal-node-value key-node)]
          (when (keyword? key) [key target]))))
    :else nil))

(s/defn case-kw-and-target
  [node :- s/Any]
  :- (s/maybe [s/Any])
  (or (ac/keyword-invoke-kw-and-target node)
      (when (and (contains? aapi/invoke-ops (:op node))
                 (= 1 (count (:args node))))
        (let [summary (get-in node [:fn :accessor-summary])
              target (first (:args node))]
          (when (and (= :unary-map-accessor (:kind summary))
                     (aapi/stable-identity-node? target))
            [(:kw summary) target])))
      (case-get-access-kw-and-target node)))

(s/defn case-kw-root-info
  [ctx :- s/Any, test-node :- s/Any]
  :- (s/maybe {s/Keyword s/Any})
  (some (fn [node]
          (when-let [[kw target] (case-kw-and-target node)]
            (when-let [root (and (keyword? kw) (case-assumption-root-for-local ctx target))]
              {:kw kw :root root})))
        (sac/ast-nodes test-node)))

(s/defn case-predicate-test-map
  [kw :- s/Any, lit :- s/Any]
  :- {s/Any s/Any}
  (cond-> {kw lit}
    (keyword? lit) (assoc lit true)))

(s/defn case-predicate-matches-lit?
  [pred :- s/Any, kw :- s/Any, lit :- s/Any]
  :- s/Bool
  (boolean
   (try
     (pred (case-predicate-test-map kw lit))
     (catch Exception _ false))))

(s/defn case-conditional-branches-from-type
  [type :- ats/SemanticType]
  :- (s/maybe s/Any)
  (let [type (if (at/maybe-type? type) (:inner type) type)]
    (cond
      (at/conditional-type? type) (:branches type)
      (at/union-type? type)
      (let [conditionals (filterv at/conditional-type? (:members type))]
        (when (= 1 (count conditionals))
          (:branches (first conditionals))))
      :else nil)))

(defn- path-elem-key
  [elem]
  (if (map? elem) (:value elem) elem))

(defn- path->test-map
  [path lit]
  (assoc-in {} (mapv path-elem-key path) lit))

(defn- path-predicate-matches-lit?
  [pred path lit]
  (boolean
   (try
     (pred (path->test-map path lit))
     (catch Exception _ false))))

(defn- descriptor-applies?
  [descriptor path]
  (and descriptor
       (= (mapv path-elem-key (:path descriptor))
          (mapv path-elem-key path))))

(defn- pred-matches-lit?
  [pred descriptor path lit]
  (if (descriptor-applies? descriptor path)
    (contains? (set (:values descriptor)) lit)
    (path-predicate-matches-lit? pred path lit)))

(s/defn narrow-conditional-by-discriminator
  "Pick branches of `branches` whose pred matches each literal in `lits`
   against discriminator `path` (non-empty vector of key-queries). Returns
   a union of selected branch types."
  [anchor-prov :- provs/Provenance, branches :- s/Any, path :- [s/Any], lits :- [s/Any]]
  :- ats/SemanticType
  (let [pick (fn [lit]
               (some (fn [[pred branch-type descriptor]]
                       (when (pred-matches-lit? pred descriptor path lit)
                         branch-type))
                     branches))
        picked (vec (distinct (keep pick lits)))]
    (if (empty? picked) (at/BottomType anchor-prov) (ato/union picked))))

(s/defn narrow-conditional-default
  "Default-branch counterpart: returns the union of branch types whose
   preds did NOT match any of `lits`."
  [anchor-prov :- provs/Provenance, branches :- s/Any, path :- [s/Any], lits :- [s/Any]]
  :- ats/SemanticType
  (let [matched? (fn [[pred _ descriptor]]
                   (some #(pred-matches-lit? pred descriptor path %) lits))
        default-types (into [] (comp (remove matched?)
                                     (map second))
                            branches)]
    (if (empty? default-types)
      (at/BottomType anchor-prov)
      (ato/union default-types))))

(s/defn case-conditional-narrow-for-lits
  [anchor-prov :- provs/Provenance, branches :- s/Any, kw-query :- s/Any, lits :- [s/Any]]
  :- ats/SemanticType
  (narrow-conditional-by-discriminator anchor-prov branches [kw-query] lits))

(s/defn case-conditional-default-narrow
  [anchor-prov :- provs/Provenance, branches :- s/Any, kw-query :- s/Any, all-lits :- [s/Any]]
  :- ats/SemanticType
  (narrow-conditional-default anchor-prov branches [kw-query] all-lits))

(s/defn annotate-case-one-then
  [anchor-prov :- provs/Provenance, ctx :- s/Any, locals :- s/Any, assumptions :- s/Any, i :- s/Int, tests :- [s/Any], thens :- [s/Any], disc-root :- s/Any, use-conditional? :- s/Any, cond-branches :- s/Any, kw-root-info :- s/Any]
  :- s/Any
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

(s/defn ^:private default-assumption
  [anchor-prov :- provs/Provenance, use-conditional? :- s/Any, disc-root :- s/Any, cond-branches :- s/Any, kw-root-info :- s/Any, all-values :- s/Any]
  :- (s/maybe aos/Assumption)
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

(s/defn annotate-case
  [{:keys [locals assumptions] :as ctx} :- s/Any, node :- s/Any]
  :- s/Any
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
