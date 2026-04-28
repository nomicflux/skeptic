(ns skeptic.analysis.annotate.control
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.base :as base]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.narrowing :as narrowing]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(defn nil-test-leaf-node
  [node]
  (case (:op node)
    :do (nil-test-leaf-node (:ret node))
    :let (nil-test-leaf-node (:body node))
    node))

(defn nil-check-local-form-in-test?
  [test-node binding-sym]
  (let [node (nil-test-leaf-node test-node)]
    (cond
      (and (= :invoke (:op node))
           (ac/type-predicate-call? (:fn node) (:args node)))
      (when (= :nil? (:pred (ac/type-predicate-assumption-info (:fn node) (:args node))))
        (let [target (first (:args node))]
          (and (= :local (:op target))
               (= (:form target) binding-sym))))

      (and (= :static-call (:op node)) (ac/static-nil?-call? node))
      (when-let [target (ac/static-nil?-target node)]
        (and (= :local (:op target))
             (= (:form target) binding-sym)))
      :else false)))

(defn if-init-nil-check-binds-same-name?
  [init-node binding-sym]
  (and (= :if (:op init-node))
       (nil-check-local-form-in-test? (:test init-node) binding-sym)))

(defn- alias-leaf-node
  [node]
  (case (:op node)
    :do (alias-leaf-node (:ret node))
    :let (alias-leaf-node (:body node))
    node))

(defn- alias-root-sym
  [ctx node]
  (some-> node alias-leaf-node (->> (ao/local-root-origin ctx)) :sym))

(defn- nilish-alias-branch?
  [node]
  (let [node (alias-leaf-node node)]
    (or (and (= :const (:op node)) (nil? (:val node)))
        (at/bottom-type? (:type node)))))

(defn- narrowing-alias-root-sym
  [ctx init-node base-origin]
  (when (= :if (:op init-node))
    (let [test-root-sym (get-in base-origin [:test :root :sym])
          then-root-sym (alias-root-sym ctx (:then init-node))
          else-root-sym (alias-root-sym ctx (:else init-node))
          source-root-sym (cond
                            (and then-root-sym (nilish-alias-branch? (:else init-node))) then-root-sym
                            (and else-root-sym (nilish-alias-branch? (:then init-node))) else-root-sym
                            :else nil)]
      (when (= test-root-sym source-root-sym)
        source-root-sym))))

(defn annotate-do
  [ctx node]
  (let [[statements final-ctx]
        (reduce (fn [[acc inner-ctx] stmt]
                  (let [annotated ((:recurse inner-ctx) inner-ctx stmt)
                        guard (ao/guard-assumption annotated)
                        next-ctx (if guard
                                   (ao/apply-guard-assumption inner-ctx guard)
                                   inner-ctx)]
                    [(conj acc annotated) next-ctx]))
                [[] ctx]
                (:statements node))
        ret ((:recurse final-ctx) final-ctx (:ret node))
        origin (aapi/node-origin ret)]
    (cond-> (assoc node :statements statements :ret ret :type (:type ret))
      origin (assoc :origin origin))))

(s/defn binding-recur-target-types
  [ctx :- s/Any bindings :- s/Any] :- [ats/SemanticType]
  (mapv (fn [binding]
          (let [type (or (:type binding) (aapi/dyn ctx))]
            (if (ato/nil-value-type? type)
              (at/->MaybeT (prov/with-ctx ctx) (aapi/dyn ctx))
              type)))
        bindings))

(defn binding-base-entry
  [ctx annotated]
  (or (ac/node-info annotated) {:type (aapi/dyn ctx)}))

(s/defn binding-alias-origin
  [init :- s/Any] :- (s/maybe aos/Origin)
  (when (and (aapi/stable-identity-node? init)
             (= :root (:kind (ao/node-origin init))))
    (let [upstream (ao/node-origin init)]
      (ao/root-origin (:sym upstream) (:type init)))))

(defn- seq-test-inner-local
  [test-node]
  (let [args (or (:args test-node) [])]
    (when (and (= 1 (count args))
               (= :local (:op (first args)))
               (contains? #{:invoke :static-call} (:op test-node)))
      (first args))))

(defn- destructure-shim?
  [binding]
  (let [init (:init binding)]
    (when (= :if (:op init))
      (let [inner (seq-test-inner-local (:test init))
            else (:else init)]
        (and inner
             (= :local (:op else))
             (= (:form inner) (:form else))
             (= (:form binding) (:form inner)))))))

(defn- shim-prior-origin
  [env binding]
  (let [inner-sym (:form (:else (:init binding)))]
    (:origin (get env inner-sym))))

(defn binding-env-entry
  [env annotated {:keys [base-entry fallback-origin track-fn-binding?]}]
  (let [init (:init annotated)
        origin (or (binding-alias-origin init)
                   (when (destructure-shim? annotated) (shim-prior-origin env annotated))
                   fallback-origin
                   (:origin base-entry))]
    (cond-> (assoc base-entry :origin origin)
      (and track-fn-binding? (= :fn (:op init)))
      (assoc :fn-binding-node init)

      (some? init)
      (assoc :binding-init init))))

(defn- annotate-let-binding
  [ctx env binding]
  (let [annotated (base/annotate-binding (assoc ctx :locals env) binding)
        init (:init annotated)
        base-entry (binding-base-entry ctx annotated)
        base-origin (:origin base-entry)
        preserve-structured-origin? (and base-origin (not= :root (:kind base-origin)))
        branch-test-sym (get-in base-origin [:test :root :sym])
        binding-sym (:form binding)
        narrowing-alias-sym (narrowing-alias-root-sym ctx init base-origin)
        self-origin (when (and (not preserve-structured-origin?)
                               (or (nil? branch-test-sym)
                                   (= branch-test-sym binding-sym)
                                   (if-init-nil-check-binds-same-name? init binding-sym)
                                   (some? narrowing-alias-sym)))
                      (ao/root-origin binding-sym (:type base-entry)))
        binding-origin (cond-> (or self-origin base-origin)
                         (and preserve-structured-origin? (symbol? binding-sym))
                         (assoc :binding-sym binding-sym))]
    [annotated
     (assoc env binding-sym
            (binding-env-entry env annotated
                               {:base-entry base-entry
                                :fallback-origin binding-origin
                                :track-fn-binding? true}))]))

(defn annotate-let
  [{:keys [locals] :as ctx} node]
  (let [[bindings final-locals]
        (reduce (fn [[acc env] binding]
                  (let [[annotated next-env] (annotate-let-binding ctx env binding)]
                    [(conj acc annotated) next-env]))
                [[] locals]
                (:bindings node))
        body ((:recurse ctx) (assoc ctx :locals final-locals) (:body node))]
    (assoc node :bindings bindings :body body :type (:type body))))

(defn- loop-recur-nodes
  [body loop-id]
  (filterv #(and (= :recur (:op %)) (= loop-id (:loop-id %)))
           (sac/ast-nodes body)))

(s/defn widen-int-loop-counter-recur-targets
  [ctx :- s/Any targets :- [ats/SemanticType] body :- s/Any loop-id :- s/Any] :- [ats/SemanticType]
  (let [recurs (loop-recur-nodes body loop-id)]
    (reduce (fn [acc recur-node]
              (let [exprs (:exprs recur-node)]
                (if (and (= (count acc) (count exprs)) (pos? (count acc)))
                        (mapv (fn [target expr]
                          (let [actual (:type expr)]
                            (if (and (at/ground-type? target)
                                     (= :int (:ground target))
                                     (or (at/numeric-dyn-type? actual)
                                         (numeric/non-int-numeric-type? actual)))
                              (aapi/normalize-type ctx actual)
                              target)))
                        acc
                        exprs)
                  acc)))
            targets
            recurs)))

(defn- collection-kind
  [type]
  (let [type (ato/normalize type)]
    (cond
      (at/map-type? type) :map
      (at/vector-type? type) :vector
      (at/set-type? type) :set
      (at/seq-type? type) :seq)))

(defn- empty-collection-type?
  [type]
  (let [type (ato/normalize type)]
    (case (collection-kind type)
      :map (empty? (:entries type))
      :vector (empty? (:items type))
      :set (empty? (:members type))
      :seq (empty? (:items type))
      false)))

(defn- widenable-empty-target?
  [target actual]
  (and (empty-collection-type? target)
       (= (collection-kind target) (collection-kind actual))
       (not (at/bottom-type? (ato/normalize actual)))))

(defn- widen-empty-target
  [original current actual]
  (if (widenable-empty-target? original actual)
    (if (at/type-equal? current original)
      (ato/normalize actual)
      (av/join (ato/derive-prov current actual) [current actual]))
    current))

(s/defn widen-empty-collection-recur-targets
  [targets :- [ats/SemanticType] body :- s/Any loop-id :- s/Any] :- [ats/SemanticType]
  (reduce (fn [acc recur-node]
            (let [exprs (:exprs recur-node)]
              (if (= (count acc) (count exprs))
                (mapv widen-empty-target targets acc (mapv :type exprs))
                acc)))
          targets
          (loop-recur-nodes body loop-id)))

(s/defn widen-loop-recur-targets
  [ctx :- s/Any targets :- [ats/SemanticType] body :- s/Any loop-id :- s/Any] :- [ats/SemanticType]
  (let [targets (widen-int-loop-counter-recur-targets ctx targets body loop-id)]
    (widen-empty-collection-recur-targets targets body loop-id)))

(defn loop-one-binding
  [ctx env binding]
  (let [annotated (base/annotate-binding (assoc ctx :locals env) binding)
        base-entry (binding-base-entry ctx annotated)]
    [annotated
     (assoc env (:form binding)
            (binding-env-entry env annotated
                               {:base-entry base-entry
                                :fallback-origin (:origin base-entry)}))]))

(defn annotate-loop-body-with-recur-target-widening
  [ctx node final-locals recur-targets loop-id targets-v0 body-v1]
  (let [targets-v1 (widen-loop-recur-targets ctx targets-v0 body-v1 loop-id)]
    (if (= targets-v1 targets-v0)
      body-v1
      ((:recurse ctx)
       (assoc ctx
              :locals final-locals
              :recur-targets (assoc recur-targets loop-id targets-v1))
       (:body node)))))

(defn annotate-loop
  [{:keys [locals recur-targets] :as ctx} node]
  (let [loop-id (:loop-id node)
        recur-targets (or recur-targets {})
        [bindings final-locals]
        (reduce (fn [[acc env] binding]
                  (let [[annotated next-env] (loop-one-binding ctx env binding)]
                    [(conj acc annotated) next-env]))
                [[] locals]
                (:bindings node))
        targets-v0 (binding-recur-target-types ctx bindings)
        recur-ctx (assoc ctx
                         :locals final-locals
                         :recur-targets (assoc recur-targets loop-id targets-v0))
        body-v1 ((:recurse recur-ctx) recur-ctx (:body node))
        body-final (annotate-loop-body-with-recur-target-widening
                    ctx node final-locals recur-targets loop-id targets-v0 body-v1)]
    (assoc node :bindings bindings :body body-final :type (:type body-final))))

(defn annotate-recur
  [{:keys [recur-targets] :as ctx} node]
  (let [exprs (mapv #((:recurse ctx) ctx %) (:exprs node))
        targets (some-> (:loop-id node) recur-targets)
        actual-argtypes (mapv #(aapi/normalize-type ctx (:type %)) exprs)]
    (cond-> (assoc node :exprs exprs :type (aapi/bottom ctx))
      (and (seq targets) (= (count targets) (count exprs)))
      (assoc :expected-argtypes (mapv #(aapi/normalize-type ctx %) targets)
             :actual-argtypes actual-argtypes))))

(defn- truthy-literal?
  [test-node]
  (when (#{:const :quote} (:op test-node))
    (let [value (ac/literal-node-value test-node)]
      (and (some? value) (not (false? value))))))

(defn- statically-truthy?
  [test-node]
  (or (truthy-literal? test-node)
      (narrowing/statically-truthy-type? (:type test-node))))

(defn- nil-const-node?
  [node]
  (and (= :const (:op node)) (nil? (:val node))))

(s/defn ^:private branch-origin
  [then-conjuncts :- [aos/Assumption] then-node :- s/Any else-node :- s/Any joined-type :- ats/SemanticType] :- aos/Origin
  (let [test (when (= 1 (count then-conjuncts))
               (first then-conjuncts))
        test (or test
                 (when (seq then-conjuncts)
                   {:kind :conjunction :parts then-conjuncts}))]
    (or (when test
          {:kind :branch
           :test test
           :then-origin (ao/node-origin then-node)
           :else-origin (ao/node-origin else-node)})
        (ao/opaque-origin joined-type))))

(s/defn ^:private branch-truth
  [then-conjuncts :- [aos/Assumption] assumptions :- [aos/Assumption]] :- (s/maybe aos/AssumptionTruth)
  (cond
    (= 1 (count then-conjuncts))
    (ao/assumption-truth (first then-conjuncts) assumptions)

    (seq then-conjuncts)
    (ao/assumption-truth {:kind :conjunction :parts (vec then-conjuncts)} assumptions)))

(defn- joined-branch-type
  [ctx truth then-node else-node]
  (case truth
    :true (:type then-node)
    :false (:type else-node)
    (av/type-join* (prov/with-ctx ctx) [(:type then-node) (:type else-node)])))

(defn annotate-if
  [{:keys [locals assumptions] :as ctx} node]
  (let [test-node ((:recurse ctx) ctx (:test node))
        regions (ao/if-test-conjuncts ctx test-node locals)
        then-conjuncts (:then-conjuncts regions)
        envs (ao/branch-local-envs ctx locals assumptions regions)
        then-node ((:recurse ctx) (assoc ctx
                                         :locals (:then-locals envs)
                                         :assumptions (:then-assumptions envs))
                   (:then node))
        else-node ((:recurse ctx) (assoc ctx
                                         :locals (:else-locals envs)
                                         :assumptions (:else-assumptions envs))
                   (:else node))
        narrow? (and (statically-truthy? test-node) (nil-const-node? else-node))
        truth (branch-truth then-conjuncts assumptions)
        joined-type (if narrow?
                      (:type then-node)
                      (joined-branch-type ctx truth then-node else-node))
        origin (if narrow?
                 (ao/node-origin then-node)
                 (branch-origin then-conjuncts then-node else-node joined-type))]
    (assoc node
           :test test-node
           :then then-node
           :else else-node
           :type joined-type
           :origin origin)))
