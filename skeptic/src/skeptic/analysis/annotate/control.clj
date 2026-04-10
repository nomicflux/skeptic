(ns skeptic.analysis.annotate.control
  (:require [skeptic.analysis.annotate.base :as aab]
            [skeptic.analysis.ast-children :as sac]
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
  "True when test is (nil? loc) or Util/identical loc nil, and loc's :form equals binding-sym."
  [test-node binding-sym]
  (let [n (nil-test-leaf-node test-node)]
    (cond
      (and (= :invoke (:op n))
           (ac/type-predicate-call? (:fn n) (:args n)))
      (when (= :nil? (:pred (ac/type-predicate-assumption-info (:fn n) (:args n))))
        (let [targ (first (:args n))]
          (and (= :local (:op targ))
               (= (:form targ) binding-sym))))

      (and (= :static-call (:op n))
           (ac/static-nil?-call? n))
      (when-let [targ (ac/static-nil?-target n)]
        (and (= :local (:op targ))
             (= (:form targ) binding-sym)))

      :else
      false)))

(defn if-init-nil-check-binds-same-name?
  [init-node binding-sym]
  (and (= :if (:op init-node))
       (nil-check-local-form-in-test? (:test init-node) binding-sym)))

(defn annotate-do
  [ctx node]
  (let [[statements final-ctx]
        (reduce (fn [[acc ctx] stmt]
                  (let [annotated ((:recurse ctx) ctx stmt)
                        guard     (ao/guard-assumption annotated)
                        new-ctx   (if guard (ao/apply-guard-assumption ctx guard) ctx)]
                    [(conj acc annotated) new-ctx]))
                [[] ctx]
                (:statements node))
        ret ((:recurse final-ctx) final-ctx (:ret node))]
    (assoc node
           :statements statements
           :ret ret
           :type (:type ret))))

(defn annotate-let
  [{:keys [locals] :as ctx} node]
  (let [[bindings final-locals]
        (reduce (fn [[acc env] binding]
                  (let [annotated (aab/annotate-binding (assoc ctx :locals env) binding)
                        init (:init annotated)
                        base-entry (or (ac/node-info annotated) {:type at/Dyn})
                        alias-origin (and (= :local (:op init))
                                          (= :root (:kind (ao/node-origin init)))
                                          (ao/root-origin (:form init) (:type init)))
                        base-origin (:origin base-entry)
                        branch-test-sym (get-in base-origin [:test :root :sym])
                        bsym (:form binding)
                        self-origin (when (or (nil? branch-test-sym)
                                             (= branch-test-sym bsym)
                                             (if-init-nil-check-binds-same-name? init bsym))
                                      (ao/root-origin bsym (:type base-entry)))
                        env-entry (cond-> (assoc base-entry :origin (or alias-origin self-origin base-origin))
                                    (= :fn (:op init))
                                    (assoc :fn-binding-node init)
                                    (some? init)
                                    (assoc :binding-init init))]
                    [(conj acc annotated)
                     (assoc env (:form binding) env-entry)]))
                [[] locals]
                (:bindings node))
        body ((:recurse ctx) (assoc ctx :locals final-locals) (:body node))]
    (assoc node
           :bindings bindings
           :body body
           :type (:type body))))

(defn- nil-value-type?
  [t]
  (and (at/value-type? t) (nil? (:value t))))

(defn- binding-recur-target-types
  [bindings]
  (mapv (fn [b]
          (let [t (or (:type b) at/Dyn)]
            (if (nil-value-type? t) (at/->MaybeT at/Dyn) t)))
        bindings))

(defn- widen-int-loop-counter-recur-targets
  "When a loop binding was inferred as :int but a recur operand is JVM Number
  (e.g. clojure.lang.Numbers dec/inc), widen that recur target to Number only.
  Does not join arbitrary types (avoids absorbing Str into an Int counter)."
  [targets body loop-id]
  (let [recurs (filterv #(and (= :recur (:op %))
                              (= loop-id (:loop-id %)))
                        (sac/ast-nodes body))]
    (reduce (fn [acc recur-node]
              (let [exprs (:exprs recur-node)]
                (if (and (= (count acc) (count exprs))
                         (pos? (count acc)))
                  (mapv (fn [t e]
                          (let [a (:type e)]
                            (if (and (at/ground-type? t) (= :int (:ground t))
                                     (at/ground-type? a)
                                     (= java.lang.Number (get-in a [:ground :class])))
                              (ato/normalize-type a)
                              t)))
                        acc
                        exprs)
                  acc)))
            targets
            recurs)))

(defn- loop-one-binding
  [ctx env binding]
  (let [annotated (aab/annotate-binding (assoc ctx :locals env) binding)
        init (:init annotated)
        base-entry (or (ac/node-info annotated) {:type at/Dyn})
        env-entry (cond-> (if (and (= :local (:op init))
                                   (= :root (:kind (ao/node-origin init))))
                            (assoc base-entry :origin (ao/root-origin (:form init)
                                                                      (:type init)))
                            base-entry)
                    (some? init)
                    (assoc :binding-init init))]
    [annotated (assoc env (:form binding) env-entry)]))

(defn annotate-loop
  [{:keys [locals recur-targets] :as ctx} node]
  (let [loop-id (:loop-id node)
        recur-targets (or recur-targets {})
        [bindings final-locals]
        (reduce (fn [[acc env] binding]
                  (let [[ann env'] (loop-one-binding ctx env binding)]
                    [(conj acc ann) env']))
                [[] locals]
                (:bindings node))
        targets-v0 (binding-recur-target-types bindings)
        recur-ctx-v0 (assoc ctx
                            :locals final-locals
                            :recur-targets (cond-> recur-targets
                                            loop-id (assoc loop-id targets-v0)))
        body-v1 ((:recurse recur-ctx-v0) recur-ctx-v0 (:body node))
        targets-v1 (widen-int-loop-counter-recur-targets targets-v0 body-v1 loop-id)
        body-final (if (= targets-v1 targets-v0)
                     body-v1
                     ((:recurse ctx) (assoc ctx
                                            :locals final-locals
                                            :recur-targets (cond-> recur-targets
                                                             loop-id (assoc loop-id targets-v1)))
                      (:body node)))]
    (assoc node
           :bindings bindings
           :body body-final
           :type (:type body-final))))

(defn annotate-recur
  [{:keys [recur-targets] :as ctx} node]
  (let [exprs (mapv #((:recurse ctx) ctx %) (:exprs node))
        targets (when-let [id (:loop-id node)]
                  (get recur-targets id))
        actual-argtypes (mapv #(ato/normalize-type (:type %)) exprs)]
    (cond-> (assoc node
                   :exprs exprs
                   :type at/BottomType)
      (and (seq targets) (= (count targets) (count exprs)))
      (assoc :expected-argtypes (mapv ato/normalize-type targets)
             :actual-argtypes actual-argtypes))))

(defn annotate-if
  [{:keys [locals assumptions] :as ctx} node]
  (let [test-node ((:recurse ctx) ctx (:test node))
        conjuncts (ao/if-test-conjuncts test-node locals)
        {:keys [then-locals then-assumptions else-locals else-assumptions]}
        (ao/branch-local-envs locals assumptions conjuncts)
        then-node ((:recurse ctx) (assoc ctx
                                         :locals then-locals
                                         :assumptions then-assumptions)
                   (:then node))
        else-node ((:recurse ctx) (assoc ctx
                                         :locals else-locals
                                         :assumptions else-assumptions)
                   (:else node))
        test-literal (when (#{:const :quote} (:op test-node))
                       (ac/literal-node-value test-node))
        test-truthy? (and (some? test-literal)
                          (not (or (false? test-literal) (nil? test-literal))))
        else-is-nil? (and (= :const (:op else-node))
                          (nil? (:val else-node)))
        narrow-null-fallback? (and test-truthy? else-is-nil?)
        type (if narrow-null-fallback?
               (:type then-node)
               (av/type-join* [(:type then-node) (:type else-node)]))
        origin (when (seq conjuncts)
                 {:kind :branch
                  :test (if (= 1 (count conjuncts))
                          (first conjuncts)
                          {:kind :conjunction :parts conjuncts})
                  :then-origin (ao/node-origin then-node)
                  :else-origin (ao/node-origin else-node)})
        origin (or (when narrow-null-fallback? (ao/node-origin then-node))
                   origin)]
    (assoc node
           :test test-node
           :then then-node
           :else else-node
           :type type
           :origin (or origin (ao/opaque-origin type)))))
