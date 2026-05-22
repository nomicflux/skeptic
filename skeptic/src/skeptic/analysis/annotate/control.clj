(ns skeptic.analysis.annotate.control
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.base :as base]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.prune :as prune]
            [skeptic.analysis.annotate.runner :as runner]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.narrowing :as narrowing]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(defn nil-test-leaf-node
  [node]
  (case (aapi/node-op node)
    :do (nil-test-leaf-node (:ret node))
    :let (nil-test-leaf-node (:body node))
    node))

(defn nil-check-local-form-in-test?
  [test-node binding-sym]
  (let [node (nil-test-leaf-node test-node)]
    (cond
      (and (aapi/invoke-node? node)
           (ac/type-predicate-call? (:fn node) (:args node)))
      (when (= :nil? (:pred (ac/type-predicate-assumption-info (:fn node) (:args node))))
        (let [target (first (:args node))]
          (and target
               (aapi/local-node? target)
               (= (:form target) binding-sym))))

      (and (aapi/static-call-node? node) (ac/static-nil?-call? node))
      (when-let [target (ac/static-nil?-target node)]
        (and (aapi/local-node? target)
             (= (:form target) binding-sym)))
      :else false)))

(defn if-init-nil-check-binds-same-name?
  [init-node binding-sym]
  (and (aapi/if-node? init-node)
       (nil-check-local-form-in-test? (:test init-node) binding-sym)))

(defn- alias-leaf-node
  [node]
  (case (aapi/node-op node)
    :do (alias-leaf-node (:ret node))
    :let (alias-leaf-node (:body node))
    node))

(defn- alias-root-sym
  [ctx node]
  (some-> node alias-leaf-node (->> (ao/local-root-origin ctx)) :sym))

(defn- nilish-alias-branch?
  [node]
  (let [node (alias-leaf-node node)]
    (or (aapi/const-nil? node)
        (at/bottom-type? (:type node)))))

(defn- narrowing-alias-root-sym
  [ctx init-node base-origin]
  (when (aapi/if-node? init-node)
    (let [test-root-sym (get-in base-origin [:test :root :sym])
          then-root-sym (alias-root-sym ctx (:then init-node))
          else-root-sym (alias-root-sym ctx (:else init-node))
          source-root-sym (cond
                            (and then-root-sym (nilish-alias-branch? (:else init-node))) then-root-sym
                            (and else-root-sym (nilish-alias-branch? (:then init-node))) else-root-sym
                            :else nil)]
      (when (= test-root-sym source-root-sym)
        source-root-sym))))

(s/defn annotate-do :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/reduce-children ctx ctx (:statements node)
   (fn [_state inner-ctx _stmt annotated]
     (let [guard (ao/guard-assumption annotated)
           contracts (ao/call-arg-contract-assumptions annotated)
           contract-assumption (case (count contracts)
                                 0 nil
                                 1 (first contracts)
                                 (ao/conjunction-assumption contracts))
           next-ctx (cond-> inner-ctx
                      guard (ao/apply-guard-assumption guard)
                      contract-assumption (ao/apply-guard-assumption contract-assumption))]
       [next-ctx next-ctx]))
   (fn [final-ctx statements]
     (runner/call (:recurse-step ctx) final-ctx (:ret node)
      (fn [ret]
        (let [origin (aapi/node-origin ret)]
          (runner/done
           (cond-> (assoc node :statements statements :ret ret :type (:type ret))
             origin (assoc :origin origin)))))))))

(s/defn binding-recur-target-types :- [at/SemanticType]
  [ctx :- s/Any bindings :- s/Any]
  (mapv (fn [binding]
          (let [type (or (:type binding) (aapi/dyn ctx))]
            (if (ato/nil-value-type? type)
              (at/->MaybeT (prov/with-ctx ctx) (aapi/dyn ctx))
              type)))
        bindings))

(defn binding-base-entry
  [ctx annotated]
  (or (ac/node-info annotated) {:type (aapi/dyn ctx)}))

(s/defn binding-alias-origin :- (s/maybe aos/RootOrigin)
  [init :- s/Any]
  (when (and (aapi/stable-identity-node? init)
             (= :root (:kind (ao/node-origin init))))
    (let [upstream (ao/node-origin init)]
      (ao/root-origin (:sym upstream) (:type init)))))

(defn- seq-test-inner-local
  [test-node]
  (let [args (or (:args test-node) [])]
    (when (and (= 1 (count args))
               (aapi/local-node? (first args))
               (or (aapi/invoke-node? test-node)
                   (aapi/static-call-node? test-node)))
      (first args))))

(defn- destructure-shim?
  [binding]
  (let [init (:init binding)]
    (when (and init (aapi/if-node? init))
      (let [inner (seq-test-inner-local (:test init))
            else (:else init)]
        (and inner
             (aapi/local-node? else)
             (= (:form inner) (:form else))
             (= (:form binding) (:form inner)))))))

(defn- shim-prior-origin
  [env binding]
  (let [inner-sym (:form (:else (:init binding)))]
    (:origin (get env inner-sym))))

(defn binding-env-entry
  [env annotated {:keys [base-entry fallback-origin track-fn-binding?]}]
  (let [init (:init annotated)
        pruned-init (some-> init prune/project-node)
        origin (or (binding-alias-origin init)
                   (when (destructure-shim? annotated) (shim-prior-origin env annotated))
                   fallback-origin
                   (:origin base-entry))]
    (cond-> (assoc base-entry :origin origin)
      (and track-fn-binding? init (aapi/fn-node? init))
      (assoc :fn-binding-node pruned-init)

      (some? init)
      (assoc :binding-init pruned-init))))

(defn- annotate-let-binding-step
  [ctx env binding k]
  (runner/call base/annotate-binding (assoc ctx :locals env) binding
   (fn [annotated]
     (let [init (:init annotated)
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
                            (assoc :binding-sym binding-sym))
           next-env (assoc env binding-sym
                           (binding-env-entry env annotated
                                              {:base-entry base-entry
                                               :fallback-origin binding-origin
                                               :track-fn-binding? true}))]
       (k annotated next-env)))))

(s/defn annotate-let :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (letfn [(walk-bindings [inner-ctx acc remaining]
            (if (empty? remaining)
              (runner/call (:recurse-step ctx) inner-ctx (:body node)
               (fn [body]
                 (runner/done
                  (assoc node :bindings acc :body body :type (:type body)))))
              (annotate-let-binding-step inner-ctx (:locals inner-ctx) (first remaining)
               (fn [annotated next-locals]
                 (let [contracts (ao/call-arg-contract-assumptions (:init annotated))
                       contract-assumption (case (count contracts)
                                             0 nil
                                             1 (first contracts)
                                             (ao/conjunction-assumption contracts))
                       next-ctx (cond-> (assoc inner-ctx :locals next-locals)
                                  contract-assumption (ao/apply-guard-assumption contract-assumption))]
                   (walk-bindings next-ctx (conj acc annotated) (rest remaining)))))))]
    (walk-bindings ctx [] (:bindings node))))

(defn- loop-recur-nodes
  [body loop-id]
  (filterv #(and (aapi/recur-node? %) (= loop-id (aapi/loop-recur-id %)))
           (sac/ast-nodes body)))

(s/defn widen-int-loop-counter-recur-targets :- [at/SemanticType]
  [ctx :- s/Any targets :- [at/SemanticType] body :- s/Any loop-id :- s/Any]
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
      (at/set-type? type) :set
      (at/seq-type? type) (:ordered-coll-kind type))))

(defn- empty-collection-type?
  [type]
  (let [type (ato/normalize type)]
    (case (collection-kind type)
      :map (empty? (:entries type))
      :vector (empty? (at/pattern-prefix (:pattern type)))
      :set (empty? (:members type))
      :sequential (empty? (at/pattern-prefix (:pattern type)))
      false)))

(defn- widenable-empty-target?
  [target actual]
  (and (empty-collection-type? target)
       (= (collection-kind target) (collection-kind actual))
       (not (at/bottom-type? (ato/normalize actual)))))

(defn- widen-empty-target
  [original current actual]
  (if (widenable-empty-target? original actual)
    (if (at/type=? current original)
      (ato/normalize actual)
      (av/join (ato/derive-prov current actual) [current actual]))
    current))

(defn- open-seq-target?
  [type]
  (let [type (ato/normalize type)]
    (and (at/seq-type? type)
         (some? (at/pattern-tail (:pattern type))))))

(defn- widenable-open-seq-target?
  [current actual]
  (and (at/seq-type? (ato/normalize current))
       (open-seq-target? actual)
       (= (collection-kind current) (collection-kind actual))))

(defn- widen-open-seq-target
  [current actual]
  (if (widenable-open-seq-target? current actual)
    (let [current (ato/normalize current)
          actual (ato/normalize actual)
          prov (ato/derive-prov current actual)
          current-elem (coll/seqish-element-type current)
          actual-elem (coll/seqish-element-type actual)
          elem (cond
                 (and current-elem actual-elem) (av/join prov [current-elem actual-elem])
                 actual-elem actual-elem
                 current-elem current-elem)]
      (if elem
        (at/->SeqT (prov/with-refs prov [(prov/of elem)])
                   (at/pattern-from-prefix-tail [] elem)
                   (:ordered-coll-kind actual))
        current))
    current))

(defn- widen-collection-target
  [original current actual]
  (let [current (widen-empty-target original current actual)]
    (widen-open-seq-target current actual)))

(s/defn widen-collection-recur-targets :- [at/SemanticType]
  [targets :- [at/SemanticType] body :- s/Any loop-id :- s/Any]
  (reduce (fn [acc recur-node]
            (let [exprs (:exprs recur-node)]
              (if (= (count acc) (count exprs))
                (mapv widen-collection-target targets acc (mapv :type exprs))
                acc)))
          targets
          (loop-recur-nodes body loop-id)))

(s/defn widen-loop-recur-targets :- [at/SemanticType]
  [ctx :- s/Any targets :- [at/SemanticType] body :- s/Any loop-id :- s/Any]
  (let [targets (widen-int-loop-counter-recur-targets ctx targets body loop-id)]
    (widen-collection-recur-targets targets body loop-id)))

(defn- loop-one-binding-step
  [ctx env binding k]
  (runner/call base/annotate-binding (assoc ctx :locals env) binding
   (fn [annotated]
     (let [base-entry (binding-base-entry ctx annotated)
           next-env (assoc env (:form binding)
                           (binding-env-entry env annotated
                                              {:base-entry base-entry
                                               :fallback-origin (:origin base-entry)}))]
       (k annotated next-env)))))

(defn- annotate-loop-body-with-recur-target-widening-step
  [ctx node final-locals recur-targets loop-id targets-v0 body-v1 k]
  (let [targets-v1 (widen-loop-recur-targets ctx targets-v0 body-v1 loop-id)]
    (if (at/type=? targets-v1 targets-v0)
      (k body-v1)
      (runner/call (:recurse-step ctx)
                   (assoc ctx
                          :locals final-locals
                          :recur-targets (assoc recur-targets loop-id targets-v1)
                          aapi/current-loop-id-key loop-id)
                   (:body node)
                   k))))

(s/defn annotate-loop :- runner/Step
  [{:keys [locals recur-targets] :as ctx} :- s/Any node :- aas/AnnotatedNode]
  (let [loop-id (gensym "skeptic-loop-")
        recur-targets (or recur-targets {})]
    (letfn [(walk-bindings [env acc remaining]
              (if (empty? remaining)
                (let [final-locals env
                      targets-v0 (binding-recur-target-types ctx acc)
                      recur-ctx (assoc ctx
                                       :locals final-locals
                                       :recur-targets (assoc recur-targets loop-id targets-v0)
                                       aapi/current-loop-id-key loop-id)]
                  (runner/call (:recurse-step ctx) recur-ctx (:body node)
                   (fn [body-v1]
                     (annotate-loop-body-with-recur-target-widening-step
                      ctx node final-locals recur-targets loop-id targets-v0 body-v1
                      (fn [body-final]
                        (runner/done
                         (assoc node :bindings acc :body body-final :type (:type body-final))))))))
                (loop-one-binding-step ctx env (first remaining)
                 (fn [annotated next-env]
                   (walk-bindings next-env (conj acc annotated) (rest remaining))))))]
      (walk-bindings locals [] (:bindings node)))))

(s/defn annotate-recur :- runner/Step
  [{:keys [recur-targets] :as ctx} :- s/Any node :- aas/AnnotatedNode]
  (runner/sequence-children ctx (:exprs node)
   (fn [exprs]
     (let [current-loop-id (get ctx aapi/current-loop-id-key)
           targets (some-> current-loop-id recur-targets)
           actual-argtypes (mapv #(aapi/normalize-type ctx (:type %)) exprs)]
       (runner/done
        (cond-> (aapi/with-loop-id (assoc node :exprs exprs :type (aapi/bottom ctx))
                                   current-loop-id)
          (and (seq targets) (= (count targets) (count exprs)))
          (assoc :expected-argtypes (mapv #(aapi/normalize-type ctx %) targets)
                 :actual-argtypes actual-argtypes)))))))

(defn- truthy-literal?
  [test-node]
  (when (aapi/const-or-quote? test-node)
    (let [value (ac/literal-node-value test-node)]
      (and (some? value) (not (false? value))))))

(defn- statically-truthy?
  [test-node]
  (or (truthy-literal? test-node)
      (narrowing/statically-truthy-type? (:type test-node))))

(defn- nil-const-node?
  [node]
  (aapi/const-nil? node))

(s/defn ^:private branch-origin :- aos/Origin
  [then-conjuncts :- [aos/Assumption] then-node :- s/Any else-node :- s/Any joined-type :- at/SemanticType]
  (let [test (when (= 1 (count then-conjuncts))
               (first then-conjuncts))
        test (or test
                 (when (seq then-conjuncts)
                   (ao/conjunction-assumption then-conjuncts)))
        then-orig (ao/node-origin then-node)
        else-orig (ao/node-origin else-node)]
    (or (when (and test then-orig else-orig)
          (ao/branch-origin test then-orig else-orig))
        (ao/opaque-origin joined-type))))

(s/defn ^:private branch-truth :- (s/maybe aos/AssumptionTruth)
  [then-conjuncts :- [aos/Assumption] assumptions :- [aos/Assumption]]
  (cond
    (= 1 (count then-conjuncts))
    (ao/assumption-truth (first then-conjuncts) assumptions)

    (seq then-conjuncts)
    (ao/assumption-truth (ao/conjunction-assumption then-conjuncts) assumptions)))

(defn- joined-branch-type
  [ctx truth then-node else-node]
  (case truth
    :true (:type then-node)
    :false (:type else-node)
    (av/type-join* (prov/with-ctx ctx) [(:type then-node) (:type else-node)])))

(s/defn annotate-if :- runner/Step
  [{:keys [locals assumptions] :as ctx} :- s/Any node :- aas/AnnotatedNode]
  (runner/call (:recurse-step ctx) ctx (:test node)
   (fn [test-node]
     (let [regions (ao/if-test-conjuncts ctx test-node locals)
           then-conjuncts (:then-conjuncts regions)
           envs (ao/branch-local-envs ctx locals assumptions regions)]
       (runner/call (:recurse-step ctx)
                    (assoc ctx
                           :locals (:then-locals envs)
                           :assumptions (:then-assumptions envs))
                    (:then node)
        (fn [then-node]
          (runner/call (:recurse-step ctx)
                       (assoc ctx
                              :locals (:else-locals envs)
                              :assumptions (:else-assumptions envs))
                       (:else node)
           (fn [else-node]
             (let [narrow? (and (statically-truthy? test-node) (nil-const-node? else-node))
                   truth (branch-truth then-conjuncts assumptions)
                   joined-type (if narrow?
                                 (:type then-node)
                                 (joined-branch-type ctx truth then-node else-node))
                   origin (if narrow?
                            (ao/node-origin then-node)
                            (branch-origin then-conjuncts then-node else-node joined-type))]
               (runner/done
                (assoc node
                       :test test-node
                       :then then-node
                       :else else-node
                       :type joined-type
                       :origin origin)))))))))))
