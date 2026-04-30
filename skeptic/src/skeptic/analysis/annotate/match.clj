(ns skeptic.analysis.annotate.match
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]
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

(s/defn case-test-literal-nodes :- (s/maybe [s/Any])
  [case-test-entry :- s/Any]
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

(s/defn case-test-literals :- [s/Any]
  [case-test-node :- s/Any]
  (mapv ac/literal-node-value (case-test-literal-nodes case-test-node)))

(s/defn case-discriminant-expr-node :- s/Any
  [test-node :- s/Any]
  (loop [n test-node]
    (if (and (= :local (:op n)) (:binding-init n))
      (recur (:binding-init n))
      n)))

(s/defn case-discriminant-leaf-node :- s/Any
  [node :- s/Any]
  (case (:op node)
    :do (case-discriminant-leaf-node (:ret node))
    :let (case-discriminant-leaf-node (:body node))
    node))

(s/defn case-assumption-root-for-local :- (s/maybe aos/RootOrigin)
  [ctx :- s/Any, target :- s/Any]
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

(s/defn case-kw-and-target :- (s/maybe [s/Any])
  [node :- s/Any]
  (or (ac/keyword-invoke-kw-and-target node)
      (when (and (contains? aapi/invoke-ops (:op node))
                 (= 1 (count (:args node))))
        (let [summary (get-in node [:fn :accessor-summary])
              target (first (:args node))]
          (when (and (= :unary-map-accessor (:kind summary))
                     (aapi/stable-identity-node? target))
            [(:kw summary) target])))
      (case-get-access-kw-and-target node)))

(s/defn case-kw-root-info :- (s/maybe {s/Keyword s/Any})
  [ctx :- s/Any, test-node :- s/Any]
  (some (fn [node]
          (when-let [[kw target] (case-kw-and-target node)]
            (when-let [root (and (keyword? kw) (case-assumption-root-for-local ctx target))]
              {:kw kw :root root})))
        (sac/ast-nodes test-node)))

(defn- case-classifier-info-for-node
  [ctx node]
  (when (and (contains? aapi/invoke-ops (:op node))
             (= 1 (count (:args node))))
    (let [summary (get-in node [:fn :accessor-summary])
          target (first (:args node))]
      (when (and (= :unary-map-classifier (:kind summary))
                 (aapi/stable-identity-node? target))
        (when-let [root (case-assumption-root-for-local ctx target)]
          {:kind :classifier
           :root root
           :path (:path summary)
           :classifier-sym (ac/resolved-call-sym (:fn node))
           :summary summary})))))

(defn- case-path-info-for-node
  [ctx node]
  (when-let [[kw target] (case-kw-and-target node)]
    (when-let [root (and (keyword? kw) (case-assumption-root-for-local ctx target))]
      {:kind :path
       :root root
       :path [kw]
       :kw kw})))

(defn- case-discriminator-info
  [ctx test-node]
  (some (fn [node]
          (or (case-classifier-info-for-node ctx node)
              (case-path-info-for-node ctx node)))
        (sac/ast-nodes test-node)))

(s/defn case-predicate-test-map :- {s/Any s/Any}
  [kw :- s/Any, lit :- s/Any]
  (cond-> {kw lit}
    (keyword? lit) (assoc lit true)))

(s/defn case-predicate-matches-lit? :- s/Bool
  [pred :- s/Any, kw :- s/Any, lit :- s/Any]
  (boolean
   (try
     (pred (case-predicate-test-map kw lit))
     (catch Exception _ false))))

(s/defn case-conditional-branches-from-type :- (s/maybe s/Any)
  [type :- ats/SemanticType]
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

(defn- discriminator-info
  [discriminator]
  (if (map? discriminator)
    discriminator
    {:kind :path :path discriminator}))

(defn- descriptor-applies?
  [descriptor discriminator]
  (let [discriminator (discriminator-info discriminator)]
    (and descriptor
         (if-let [classifier-sym (:classifier-sym descriptor)]
           (= classifier-sym (:classifier-sym discriminator))
           (= (mapv path-elem-key (:path descriptor))
              (mapv path-elem-key (:path discriminator)))))))

(defn- pred-matches-lit?
  [pred descriptor discriminator lit]
  (let [discriminator (discriminator-info discriminator)]
    (cond
      (descriptor-applies? descriptor discriminator)
      (contains? (set (:values descriptor)) lit)

      (:classifier-sym discriminator)
      false

      :else
      (path-predicate-matches-lit? pred (:path discriminator) lit))))

(defn- discriminator-root
  [discriminator]
  (:root (discriminator-info discriminator)))

(defn- root-discriminator?
  [discriminator]
  (= :root (:kind (discriminator-info discriminator))))

(defn- conditional-discriminator?
  [discriminator]
  (contains? #{:path :classifier} (:kind (discriminator-info discriminator))))

(s/defn narrow-conditional-by-discriminator :- ats/SemanticType
  "Pick branches of `branches` whose pred matches each literal in `lits`
   against `discriminator` (path vector or discriminator info map). Returns
   a union of selected branch types."
  [anchor-prov :- provs/Provenance, branches :- s/Any, discriminator :- s/Any, lits :- [s/Any]]
  (let [pick (fn [lit]
               (some (fn [[pred branch-type descriptor]]
                       (when (pred-matches-lit? pred descriptor discriminator lit)
                         branch-type))
                     branches))
        picked (vec (distinct (keep pick lits)))]
    (if (empty? picked) (at/BottomType anchor-prov) (ato/union picked))))

(s/defn narrow-conditional-default :- ats/SemanticType
  "Default-branch counterpart: returns the union of branch types whose
   preds did NOT match any of `lits`."
  [anchor-prov :- provs/Provenance, branches :- s/Any, discriminator :- s/Any, lits :- [s/Any]]
  (let [matched? (fn [[pred _ descriptor]]
                   (some #(pred-matches-lit? pred descriptor discriminator %) lits))
        default-types (into [] (comp (remove matched?)
                                     (map second))
                            branches)]
    (if (empty? default-types)
      (at/BottomType anchor-prov)
      (ato/union default-types))))

(s/defn case-conditional-narrow-for-lits :- ats/SemanticType
  [anchor-prov :- provs/Provenance, branches :- s/Any, discriminator :- s/Any, lits :- [s/Any]]
  (narrow-conditional-by-discriminator anchor-prov branches discriminator lits))

(s/defn case-conditional-default-narrow :- ats/SemanticType
  [anchor-prov :- provs/Provenance, branches :- s/Any, discriminator :- s/Any, all-lits :- [s/Any]]
  (narrow-conditional-default anchor-prov branches discriminator all-lits))

(s/defn annotate-case-one-then :- s/Any
  [anchor-prov :- provs/Provenance, ctx :- s/Any, locals :- s/Any, assumptions :- s/Any, i :- s/Int, tests :- [s/Any], thens :- [s/Any], discriminator :- s/Any, use-conditional? :- s/Any, cond-branches :- s/Any]
  (let [lits (vec (distinct (case-test-literals (nth tests i))))
        disc-root (discriminator-root discriminator)
        assumption (cond
                     (and use-conditional? disc-root (seq lits))
                     {:kind :conditional-branch
                      :root disc-root
                      :narrowed-type (case-conditional-narrow-for-lits
                                      anchor-prov cond-branches discriminator lits)
                      :polarity true}

                     (and (root-discriminator? discriminator) disc-root (seq lits))
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

(s/defn ^:private default-assumption :- (s/maybe aos/Assumption)
  [anchor-prov :- provs/Provenance, use-conditional? :- s/Any, discriminator :- s/Any, cond-branches :- s/Any, all-values :- s/Any]
  (let [disc-root (discriminator-root discriminator)]
    (cond
      (and use-conditional? disc-root (seq all-values))
      {:kind :conditional-branch
       :root disc-root
       :narrowed-type (case-conditional-default-narrow
                       anchor-prov cond-branches discriminator all-values)
       :polarity true}

      (and (root-discriminator? discriminator) disc-root (seq all-values))
      {:kind :value-equality
       :root disc-root
       :values all-values
       :polarity false}
      :else nil)))

(defn- exhaustive-values?
  [type values]
  (and type
       (seq values)
       (sums/exhausted-by-values? type values)))

(defn- case-joined-type
  [anchor-prov branch-types default-node exhaustive?]
  (av/type-join* anchor-prov
                 (if exhaustive?
                   branch-types
                   (conj branch-types (:type default-node)))))

(s/defn annotate-case :- aas/AnnotatedNode
  [{:keys [locals assumptions] :as ctx} :- s/Any, node :- aas/CaseNode]
  (let [anchor-prov (prov/with-ctx ctx)
        test-node ((:recurse ctx) ctx (:test node))
        discriminant-expr (case-discriminant-expr-node test-node)
        tests (:tests node)
        thens (:thens node)
        n (min (count tests) (count thens))
        discriminator (or (case-discriminator-info ctx discriminant-expr)
                          (when-let [leaf (some-> discriminant-expr case-discriminant-leaf-node)]
                            (when-let [root (case-assumption-root-for-local ctx leaf)]
                              {:kind :root
                               :root root
                               :type (:type root)})))
        cond-branches (when (and discriminator (conditional-discriminator? discriminator))
                        (case-conditional-branches-from-type (:type (discriminator-root discriminator))))
        use-conditional? (seq cond-branches)
        all-values (into [] (distinct (mapcat case-test-literals (take n tests))))
        annotated-thens (mapv (fn [i]
                                (annotate-case-one-then
                                 anchor-prov ctx locals assumptions i tests thens
                                 discriminator use-conditional? cond-branches))
                              (range n))
        assumption (default-assumption anchor-prov use-conditional? discriminator cond-branches all-values)
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
        exhaustive? (exhaustive-values? (:type test-node) all-values)
        joined (case-joined-type (prov/with-ctx ctx) branch-types default-node exhaustive?)]
    (assoc node
           :test test-node
           :tests (vec (take n tests))
           :thens annotated-thens
           :default default-node
           :type joined
           :origin (ao/opaque-origin joined))))
