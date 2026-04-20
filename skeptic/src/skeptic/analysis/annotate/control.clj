(ns skeptic.analysis.annotate.control
  (:require [skeptic.analysis.annotate.base :as base]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]))

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
  [node]
  (some-> node alias-leaf-node ao/local-root-origin :sym))

(defn- nilish-alias-branch?
  [node]
  (let [node (alias-leaf-node node)]
    (or (and (= :const (:op node)) (nil? (:val node)))
        (at/bottom-type? (:type node)))))

(defn- narrowing-alias-root-sym
  [init-node base-origin]
  (when (= :if (:op init-node))
    (let [test-root-sym (get-in base-origin [:test :root :sym])
          then-root-sym (alias-root-sym (:then init-node))
          else-root-sym (alias-root-sym (:else init-node))
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
        ret ((:recurse final-ctx) final-ctx (:ret node))]
    (assoc node :statements statements :ret ret :type (:type ret))))

(defn binding-recur-target-types
  [bindings]
  (mapv (fn [binding]
          (let [type (or (:type binding) at/Dyn)]
            (if (ato/nil-value-type? type) (at/->MaybeT at/Dyn) type)))
        bindings))

(defn binding-base-entry
  [annotated]
  (or (ac/node-info annotated) {:type at/Dyn}))

(defn binding-alias-origin
  [init]
  (and (= :local (:op init))
       (= :root (:kind (ao/node-origin init)))
       (ao/root-origin (:form init) (:type init))))

(defn binding-env-entry
  [annotated {:keys [base-entry fallback-origin track-fn-binding?]}]
  (let [init (:init annotated)
        base-entry (or base-entry (binding-base-entry annotated))
        origin (or (binding-alias-origin init)
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
        base-entry (binding-base-entry annotated)
        base-origin (:origin base-entry)
        preserve-structured-origin? (= :map-key-lookup (:kind base-origin))
        branch-test-sym (get-in base-origin [:test :root :sym])
        binding-sym (:form binding)
        narrowing-alias-sym (narrowing-alias-root-sym init base-origin)
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
            (binding-env-entry annotated
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

(defn widen-int-loop-counter-recur-targets
  [targets body loop-id]
  (let [recurs (filterv #(and (= :recur (:op %)) (= loop-id (:loop-id %)))
                        (sac/ast-nodes body))]
    (reduce (fn [acc recur-node]
              (let [exprs (:exprs recur-node)]
                (if (and (= (count acc) (count exprs)) (pos? (count acc)))
                        (mapv (fn [target expr]
                          (let [actual (:type expr)]
                            (if (and (at/ground-type? target)
                                     (= :int (:ground target))
                                     (or (at/numeric-dyn-type? actual)
                                         (numeric/non-int-numeric-type? actual)))
                              (ato/normalize-type actual)
                              target)))
                        acc
                        exprs)
                  acc)))
            targets
            recurs)))

(defn loop-one-binding
  [ctx env binding]
  (let [annotated (base/annotate-binding (assoc ctx :locals env) binding)
        base-entry (binding-base-entry annotated)]
    [annotated
     (assoc env (:form binding)
            (binding-env-entry annotated
                               {:base-entry base-entry
                                :fallback-origin (:origin base-entry)}))]))

(defn annotate-loop-body-with-recur-target-widening
  [ctx node final-locals recur-targets loop-id targets-v0 body-v1]
  (let [targets-v1 (widen-int-loop-counter-recur-targets targets-v0 body-v1 loop-id)]
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
        targets-v0 (binding-recur-target-types bindings)
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
        actual-argtypes (mapv #(ato/normalize-type (:type %)) exprs)]
    (cond-> (assoc node :exprs exprs :type at/BottomType)
      (and (seq targets) (= (count targets) (count exprs)))
      (assoc :expected-argtypes (mapv ato/normalize-type targets)
             :actual-argtypes actual-argtypes))))

(defn- truthy-literal?
  [test-node]
  (when (#{:const :quote} (:op test-node))
    (let [value (ac/literal-node-value test-node)]
      (and (some? value) (not (false? value))))))

(defn- nil-const-node?
  [node]
  (and (= :const (:op node)) (nil? (:val node))))

(defn- branch-origin
  [conjuncts then-node else-node joined-type]
  (let [test (when (= 1 (count conjuncts))
               (first conjuncts))
        test (or test
                 (when (seq conjuncts)
                   {:kind :conjunction :parts conjuncts}))]
    (or (when test
          {:kind :branch
           :test test
           :then-origin (ao/node-origin then-node)
           :else-origin (ao/node-origin else-node)})
        (ao/opaque-origin joined-type))))

(defn annotate-if
  [{:keys [locals assumptions] :as ctx} node]
  (let [test-node ((:recurse ctx) ctx (:test node))
        conjuncts (ao/if-test-conjuncts test-node locals)
        envs (ao/branch-local-envs locals assumptions conjuncts)
        then-node ((:recurse ctx) (assoc ctx
                                         :locals (:then-locals envs)
                                         :assumptions (:then-assumptions envs))
                   (:then node))
        else-node ((:recurse ctx) (assoc ctx
                                         :locals (:else-locals envs)
                                         :assumptions (:else-assumptions envs))
                   (:else node))
        narrow? (and (truthy-literal? test-node) (nil-const-node? else-node))
        joined-type (if narrow?
                      (:type then-node)
                      (av/type-join* [(:type then-node) (:type else-node)]))
        origin (if narrow?
                 (ao/node-origin then-node)
                 (branch-origin conjuncts then-node else-node joined-type))]
    (assoc node
           :test test-node
           :then then-node
           :else else-node
           :type joined-type
           :origin origin)))
