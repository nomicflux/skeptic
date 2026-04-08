(ns skeptic.analysis.annotate
  (:require [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.native-fns :as anf]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]))

(defn annotate-children
  [ctx node]
  (reduce (fn [acc key]
            (let [value (get acc key)
                  annotated (if (vector? value)
                              (mapv #((:recurse ctx) ctx %) value)
                              ((:recurse ctx) ctx value))]
              (assoc acc key annotated)))
          node
          (:children node)))

(defn annotate-const
  [_ctx node]
  (let [type (av/type-of-value (:val node))]
    (assoc node
           :type type)))

(defn annotate-binding
  [ctx node]
  (if-let [init (:init node)]
    (let [annotated-init ((:recurse ctx) ctx init)]
      (merge node
             {:init annotated-init}
             (ac/node-info annotated-init)))
    node))

(defn annotate-local
  [{:keys [locals assumptions]} node]
  (merge node
         (if-let [entry (get locals (:form node))]
           (ao/effective-entry (:form node) entry assumptions)
           {:type at/Dyn})))

(defn annotate-var-like
  [{:keys [dict ns]} node]
  (merge node
         (or (ac/lookup-entry dict ns node)
             {:type at/Dyn})))

(defn arg-type-specs
  [dict ns-sym name params]
  (let [entry (when-some [sym name]
                (or (get dict sym)
                    (get dict (ac/qualify-symbol ns-sym sym))))
        arg-specs (get-in (an/normalize-entry entry) [:arglists (count params) :types])]
    (or arg-specs
        (mapv (fn [param]
                {:type at/Dyn
                 :optional? false
                 :name (:form param)})
              params))))

(defn annotate-fn-method
  [{:keys [locals dict name ns recur-targets] :as ctx} node & [param-type-overrides]]
  (let [param-type-overrides (or param-type-overrides {})
        raw-specs (arg-type-specs dict ns name (:params node))
        param-specs (mapv (fn [param spec]
                            (if-let [t (get param-type-overrides (:form param))]
                              (assoc spec :type (ato/normalize-type t))
                              spec))
                          (:params node)
                          raw-specs)
        annotated-params (mapv (fn [param spec]
                                 (let [extra (when (at/fun-type? (:type spec))
                                               (ac/fun-type->call-opts (:type spec)))]
                                   (merge param spec extra)))
                               (:params node)
                               param-specs)
        param-locals (into locals
                           (map (fn [param]
                                  [(:form param) (assoc (ac/node-info param) :binding-init nil)]))
                           annotated-params)
        recur-targets (cond-> (or recur-targets {})
                        (:loop-id node)
                        (assoc (:loop-id node) (mapv :type annotated-params)))
        body ((:recurse ctx) (assoc ctx
                                    :locals param-locals
                                    :recur-targets recur-targets)
                             (:body node))]
    (assoc node
           :params annotated-params
           :body body
           :type (:type body)
           :output-type (:type body)
           :arglist (mapv :name param-specs)
           :param-specs param-specs)))

(defn method->arglist-entry
  [method]
  {:arglist (:arglist method)
   :count (count (:param-specs method))
   :types (mapv (fn [{:keys [type name]}]
                  {:type type
                   :optional? false
                   :name name})
                 (:param-specs method))})

(defn annotate-fn
  [ctx node & [opts]]
  (let [overrides (:param-type-overrides opts {})
        methods (mapv #(annotate-fn-method ctx % overrides) (:methods node))
        arglists (into {}
                       (map (fn [method]
                              [(count (:param-specs method))
                               (method->arglist-entry method)]))
                       methods)
        output-type (av/type-join* (map :output-type methods))
        fn-type (at/->FunT
                 (mapv (fn [method]
                         (at/->FnMethodT (mapv :type (:param-specs method))
                                         (:output-type method)
                                         (count (:param-specs method))
                                         false))
                       methods))]
    (assoc node
           :methods methods
           :output-type output-type
           :arglists arglists
           :type fn-type)))

(defn annotate-instance-call
  [ctx node]
  (let [instance ((:recurse ctx) ctx (:instance node))
        args (mapv #((:recurse ctx) ctx %) (:args node))
        method (:method node)
        it (:type instance)
        output (when (#{'nth} method)
                 (aac/instance-nth-element-type it (first args)))]
    (assoc node
           :instance instance
           :args args
           :type (or output at/Dyn))))

(defn- reduce-assoc-pairs [m-type kv-pairs]
  (reduce (fn [t [kn vn]]
            (let [lk (when (ac/literal-map-key? kn) (ac/literal-node-value kn))]
              (if (keyword? lk) (amoa/assoc-type t lk (:type vn)) t)))
          m-type kv-pairs))

(defn- reduce-dissoc-keys [m-type key-nodes]
  (reduce (fn [t kn]
            (let [lk (when (ac/literal-map-key? kn) (ac/literal-node-value kn))]
              (if (keyword? lk) (amoa/dissoc-type t lk) t)))
          m-type key-nodes))

(defn annotate-static-call
  [ctx node]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))
        actual-argtypes (mapv :type args)
        native-info (anf/static-call-native-info (:class node) (:method node) (count args))
        default-expected (vec (repeat (count args) at/Dyn))
        expected-argtypes (if native-info
                            (:expected-argtypes native-info)
                            default-expected)
        type (cond
                 (ac/static-get-call? node)
                 (let [[target key-node default-node] args
                       key-type (ac/get-key-query key-node)]
                   (if default-node
                     (amo/map-get-type (:type target)
                                       key-type
                                       (:type default-node))
                     (amo/map-get-type (:type target)
                                       key-type)))

                 (ac/static-merge-call? node)
                 (amoa/merge-types (map :type args))

                 (and (ac/static-assoc-call? node)
                      (>= (count args) 3))
                 (let [[m & kvs] args]
                   (reduce-assoc-pairs (:type m) (partition 2 kvs)))

                 (and (ac/static-dissoc-call? node)
                      (>= (count args) 2))
                 (let [[m & ks] args]
                   (reduce-dissoc-keys (:type m) ks))

                 (and (ac/static-update-call? node)
                      (>= (count args) 3))
                 (let [[m kn uf] args
                       lk (when (ac/literal-map-key? kn)
                            (ac/literal-node-value kn))]
                   (if (keyword? lk)
                     (amoa/update-type (:type m) lk (:type uf))
                     at/Dyn))

                 (ac/static-contains-call? node)
                 aan/bool-type

                 (and (ac/seq-call? node)
                      (= 1 (count args)))
                 (let [t (:type (first args))]
                   (or (cond
                         (at/seq-type? t) t
                         (at/vector-type? t) (aac/vector-to-homogeneous-seq-type t)
                         :else nil)
                       at/Dyn))

                 native-info
                 (aan/narrow-static-numbers-output node args actual-argtypes native-info)

                 :else
                 at/Dyn)]
    (assoc node
           :args args
           :actual-argtypes actual-argtypes
           :expected-argtypes expected-argtypes
           :type type)))

(defn- unary-fn-invoke-with-arg-type-hint?
  [ctx fn-ast node]
  (or (and (= :fn (:op fn-ast))
           (= 1 (count (:methods fn-ast)))
           (= 1 (count (:args node))))
      (and (= :local (:op fn-ast))
           (let [e (get (:locals ctx) (:form fn-ast))
                 fnode (:fn-binding-node e)]
             (and fnode
                  (= :fn (:op fnode))
                  (= 1 (count (:methods fnode)))
                  (= 1 (count (:args node))))))))

(defn- annotate-unary-fn-invoke-with-arg-type-hint
  [ctx fn-ast node]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))
        [src-fn pform]
        (if (= :fn (:op fn-ast))
          [fn-ast (:form (first (:params (first (:methods fn-ast)))))]
          (let [e (get (:locals ctx) (:form fn-ast))
                fnode (:fn-binding-node e)]
            [fnode (:form (first (:params (first (:methods fnode)))))]))
        ovs {pform (or (:type (first args)) at/Dyn)}
        fn-node (annotate-fn ctx src-fn {:param-type-overrides ovs})]
    [fn-node args]))

(defn annotate-invoke
  [ctx node]
  (let [fn-ast (:fn node)
        hint? (unary-fn-invoke-with-arg-type-hint? ctx fn-ast node)
        [fn-node args]
        (if hint?
          (annotate-unary-fn-invoke-with-arg-type-hint ctx fn-ast node)
          [((:recurse ctx) ctx fn-ast)
           (mapv #((:recurse ctx) ctx %) (:args node))])
        {:keys [expected-argtypes output-type fn-type]} (ac/call-info fn-node args)
        output-type (cond
                      (ac/get-call? fn-node)
                      (let [[target key-node default-node] args
                            key-type (ac/get-key-query key-node)]
                        (if default-node
                          (amo/map-get-type (:type target)
                                            key-type
                                            (:type default-node))
                          (amo/map-get-type (:type target)
                                            key-type)))

                      (ac/merge-call? fn-node)
                      (amoa/merge-types (map :type args))

                      (and (ac/assoc-call? fn-node)
                           (>= (count args) 3))
                      (let [[m & kvs] args]
                        (reduce-assoc-pairs (:type m) (partition 2 kvs)))

                      (and (ac/dissoc-call? fn-node)
                           (>= (count args) 2))
                      (let [[m & ks] args]
                        (reduce-dissoc-keys (:type m) ks))

                      (and (ac/update-call? fn-node)
                           (>= (count args) 3))
                      (let [[m kn uf] args
                            lk (when (ac/literal-map-key? kn)
                                 (ac/literal-node-value kn))]
                        (if (keyword? lk)
                          (amoa/update-type (:type m) lk (:type uf))
                          output-type))

                      (ac/contains-call? fn-node)
                      aan/bool-type

                      (and (ac/first-call? fn-node)
                           (= 1 (count args)))
                      (or (aac/coll-first-type (:type (first args)))
                          output-type)

                      (and (ac/second-call? fn-node)
                           (= 1 (count args)))
                      (or (aac/coll-second-type (:type (first args)))
                          output-type)

                      (and (ac/last-call? fn-node)
                           (= 1 (count args)))
                      (or (aac/coll-last-type (:type (first args)))
                          output-type)

                      (and (ac/nth-call? fn-node)
                           (>= (count args) 2))
                      (or (aac/invoke-nth-output-type args)
                          output-type)

                      (and (ac/rest-call? fn-node)
                           (= 1 (count args)))
                      (or (aac/coll-rest-output-type (:type (first args)))
                          output-type)

                      (and (ac/butlast-call? fn-node)
                           (= 1 (count args)))
                      (or (aac/coll-butlast-output-type (:type (first args)))
                          output-type)

                      (and (ac/drop-last-call? fn-node)
                           (or (= 1 (count args)) (= 2 (count args))))
                      (or (if (= 1 (count args))
                            (aac/coll-drop-last-output-type (:type (first args)) 1)
                            (when-let [n (aac/const-long-value (first args))]
                              (aac/coll-drop-last-output-type (:type (second args)) n)))
                          output-type)

                      (and (ac/take-call? fn-node)
                           (= 2 (count args)))
                      (or (when-let [n (aac/const-long-value (first args))]
                            (aac/coll-take-prefix-type (:type (second args)) n))
                          (aac/coll-same-element-seq-type (:type (second args)))
                          output-type)

                      (and (ac/drop-call? fn-node)
                           (= 2 (count args)))
                      (or (when-let [n (aac/const-long-value (first args))]
                            (aac/coll-drop-prefix-type (:type (second args)) n))
                          (aac/coll-same-element-seq-type (:type (second args)))
                          output-type)

                      (and (ac/take-while-call? fn-node)
                           (= 2 (count args)))
                      (or (aac/coll-same-element-seq-type (:type (second args)))
                          output-type)

                      (and (ac/drop-while-call? fn-node)
                           (= 2 (count args)))
                      (or (aac/coll-same-element-seq-type (:type (second args)))
                          output-type)

                      (ac/concat-call? fn-node)
                      (or (aac/concat-output-type args)
                          output-type)

                      (ac/into-call? fn-node)
                      (or (aac/into-output-type args)
                          output-type)

                      (and (ac/chunk-first-call? fn-node) (= 1 (count args)))
                      (or (when-let [e (aac/seqish-element-type (:type (first args)))]
                            (at/->SeqT [(ato/normalize-type e)] true))
                          output-type)

                      (and (ac/seq-call? fn-node)
                           (= 1 (count args)))
                      (let [t (:type (first args))]
                        (or (cond
                              (at/seq-type? t) t
                              (at/vector-type? t) (aac/vector-to-homogeneous-seq-type t)
                              :else nil)
                            output-type))

                      :else
                      output-type)
        narrow-t (aan/invoke-integral-math-narrow-type fn-node args (mapv :type args))
        output-type (or narrow-t output-type)]
    (assoc node
           :fn fn-node
           :args args
           :actual-argtypes (mapv :type args)
           :expected-argtypes (mapv ato/normalize-type expected-argtypes)
           :type (ato/normalize-type output-type)
           :fn-type (ato/normalize-type fn-type))))

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
                  (let [annotated (annotate-binding (assoc ctx :locals env) binding)
                        init (:init annotated)
                        base-entry (or (ac/node-info annotated) {:type at/Dyn})
                        alias-origin (and (= :local (:op init))
                                          (= :root (:kind (ao/node-origin init)))
                                          (ao/root-origin (:form init) (:type init)))
                        base-origin (:origin base-entry)
                        branch-test-sym (get-in base-origin [:test :root :sym])
                        self-origin (when (or (nil? branch-test-sym)
                                             (= branch-test-sym (:form binding)))
                                      (ao/root-origin (:form binding) (:type base-entry)))
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

(defn annotate-loop
  [{:keys [locals recur-targets] :as ctx} node]
  (let [loop-id (:loop-id node)
        recur-targets (or recur-targets {})
        [bindings final-locals]
        (reduce (fn [[acc env] binding]
                  (let [annotated (annotate-binding (assoc ctx :locals env) binding)
                        init (:init annotated)
                        base-entry (or (ac/node-info annotated) {:type at/Dyn})
                        env-entry (cond-> (if (and (= :local (:op init))
                                                     (= :root (:kind (ao/node-origin init))))
                                              (assoc base-entry :origin (ao/root-origin (:form init)
                                                                                        (:type init)))
                                              base-entry)
                                    (some? init)
                                    (assoc :binding-init init))]
                    [(conj acc annotated)
                     (assoc env (:form binding) env-entry)]))
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
        assumption (ao/test->assumption test-node)
        {:keys [then-locals then-assumptions else-locals else-assumptions]}
        (ao/branch-local-envs locals assumptions assumption)
        then-node ((:recurse ctx) (assoc ctx
                                         :locals then-locals
                                         :assumptions then-assumptions)
                   (:then node))
        else-node ((:recurse ctx) (assoc ctx
                                         :locals else-locals
                                         :assumptions else-assumptions)
                   (:else node))
        type (av/type-join* [(:type then-node) (:type else-node)])
        origin (when assumption
                 {:kind :branch
                  :test assumption
                  :then-origin (ao/node-origin then-node)
                  :else-origin (ao/node-origin else-node)})]
    (assoc node
           :test test-node
           :then then-node
           :else else-node
           :type type
           :origin (or origin (ao/opaque-origin type)))))

(defn- case-test-literal-nodes
  [case-test-node]
  (when case-test-node
    (let [raw (or (:tests case-test-node)
                  (when-let [t (:test case-test-node)]
                    t))]
      (when raw
        (let [nodes (if (vector? raw) raw [raw])]
          (vec (filter #(#{:const :quote} (:op %)) nodes)))))))

(defn- case-test-literals
  [case-test-node]
  (mapv ac/literal-node-value (case-test-literal-nodes case-test-node)))

(defn annotate-case
  [{:keys [locals assumptions] :as ctx} node]
  (let [test-node ((:recurse ctx) ctx (:test node))
        tests (:tests node)
        thens (:thens node)
        default (:default node)
        n (min (count tests) (count thens))
        root (ao/local-root-origin test-node)
        all-values (into [] (distinct (mapcat case-test-literals (take n tests))))
        annotated-thens
        (mapv (fn [i]
                (let [lits (vec (distinct (case-test-literals (nth tests i))))
                      assumption (when (and root (seq lits))
                                   {:kind :value-equality
                                    :root root
                                    :values lits
                                    :polarity true})
                      {:keys [then-locals then-assumptions]}
                      (ao/branch-local-envs locals assumptions assumption)
                      then-body (:then (nth thens i))
                      ann ((:recurse ctx) (assoc ctx
                                               :locals then-locals
                                               :assumptions then-assumptions)
                           then-body)]
                  (assoc (nth thens i) :then ann)))
              (range n))
        default-assumption (when (and root (seq all-values))
                             {:kind :value-equality
                              :root root
                              :values all-values
                              :polarity false})
        {:keys [then-locals then-assumptions]}
        (ao/branch-local-envs locals assumptions default-assumption)
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

(defn annotate-def
  [{:keys [locals] :as ctx} node]
  (let [meta-node (when-some [meta-node (:meta node)]
                    ((:recurse ctx) ctx meta-node))
        init-node (when-some [init-node (:init node)]
                    ((:recurse ctx) (assoc ctx
                                           :locals locals
                                           :name (:name node))
                     init-node))]
    (cond-> (assoc node
                   :type (at/->VarT (or (:type init-node) at/Dyn)))
      meta-node (assoc :meta meta-node)
      init-node (assoc :init init-node))))

(defn annotate-vector
  [ctx node]
  (let [items (mapv #((:recurse ctx) ctx %) (:items node))
        item-types (mapv #(ato/normalize-type (or (:type %) at/Dyn)) items)]
    (assoc node
           :items items
           :type (at/->VectorT item-types (aac/vec-homogeneous-items? item-types)))))

(defn annotate-set
  [ctx node]
  (let [items (mapv #((:recurse ctx) ctx %) (:items node))]
    (assoc node
           :items items
           :type (ato/normalize-type
                  #{(if (seq items)
                      (av/type-join* (map :type items))
                      at/Dyn)}))))

(defn annotate-map
  [ctx node]
  (let [keys (mapv #((:recurse ctx) ctx %) (:keys node))
        vals (mapv #((:recurse ctx) ctx %) (:vals node))]
    (assoc node
           :keys keys
           :vals vals
           :type (ato/normalize-type
                  (into {}
                        (map (fn [k v]
                               [(ac/map-literal-key-type k)
                                (:type v)])
                             keys
                             vals))))))

(defn annotate-new
  [ctx node]
  (let [class-node ((:recurse ctx) ctx (:class node))
        args (mapv #((:recurse ctx) ctx %) (:args node))]
    (assoc node
           :class class-node
           :args args
           :type (or (aac/lazy-seq-new-type class-node args)
                 (some-> (:val class-node) av/class->type)
                 at/Dyn))))

(defn annotate-with-meta
  [ctx node]
  (let [meta-node ((:recurse ctx) ctx (:meta node))
        expr-node ((:recurse ctx) ctx (:expr node))]
    (merge node
           {:meta meta-node
            :expr expr-node}
           (ac/node-info expr-node))))

(defn annotate-throw
  [ctx node]
  (let [exception ((:recurse ctx) ctx (:exception node))]
    (assoc node
           :exception exception
           :type at/BottomType)))

(defn annotate-catch
  [{:keys [locals] :as ctx} node]
  (let [class-node ((:recurse ctx) ctx (:class node))
        caught-type (or (some-> (:val class-node) av/class->type)
                        at/Dyn)
        local-node (merge (:local node)
                          {:type caught-type})
        body ((:recurse ctx) (assoc ctx
                                    :locals (assoc locals (:form (:local node))
                                                   {:type caught-type}))
              (:body node))]
    (assoc node
           :class class-node
           :local local-node
           :body body
           :type (:type body))))

(defn annotate-try
  [ctx node]
  (let [body ((:recurse ctx) ctx (:body node))
        catches (mapv #(annotate-catch ctx %) (:catches node))
        finally-node (when-some [finally-node (:finally node)]
                       ((:recurse ctx) ctx finally-node))]
    (cond-> (assoc node
                   :body body
                   :catches catches
                   :type (av/type-join* (cons (:type body)
                                              (map :type catches))))
      finally-node (assoc :finally finally-node))))

(defn annotate-quote
  [ctx node]
  (let [expr ((:recurse ctx) ctx (:expr node))]
    (assoc node
           :expr expr
           :type (av/type-of-value (-> node :form second)))))

(defn node-location
  [node]
  (select-keys (meta (:form node)) [:file :line :column :end-line :end-column]))

(defn node-error-context
  [node]
  (let [expr (:form node)
        source-expression (:source (meta expr))]
    {:expr expr
     :source-expression source-expression
     :location (node-location node)}))

(defn annotate-node
  [ctx node]
  (let [ctx (assoc ctx :recurse annotate-node)]
    (abl/with-error-context (node-error-context node)
      (abr/strip-derived-types
       (case (:op node)
       :binding (annotate-binding ctx node)
       :const (annotate-const ctx node)
       :def (annotate-def ctx node)
       :do (annotate-do ctx node)
       :fn (annotate-fn ctx node)
       :fn-method (annotate-fn-method ctx node)
       :if (annotate-if ctx node)
       :case (annotate-case ctx node)
       :instance-call (annotate-instance-call ctx node)
       :invoke (annotate-invoke ctx node)
       :let (annotate-let ctx node)
       :loop (annotate-loop ctx node)
       :local (annotate-local ctx node)
       :map (annotate-map ctx node)
       :new (annotate-new ctx node)
       :quote (annotate-quote ctx node)
       :recur (annotate-recur ctx node)
       :set (annotate-set ctx node)
       :static-call (annotate-static-call ctx node)
       :the-var (annotate-var-like ctx node)
       :throw (annotate-throw ctx node)
       :try (annotate-try ctx node)
       :var (annotate-var-like ctx node)
       :vector (annotate-vector ctx node)
       :with-meta (annotate-with-meta ctx node)
       (assoc (annotate-children ctx node)
              :type at/Dyn))))))

(defn annotate-ast
  ([dict ast]
   (annotate-ast dict ast {}))
  ([dict ast {:keys [locals name ns assumptions]}]
   (annotate-node {:dict dict
                   :locals (into {}
                                 (map (fn [[sym entry]]
                                        [sym (an/normalize-entry entry)]))
                                 locals)
                   :assumptions (vec assumptions)
                   :recur-targets {}
                   :name name
                   :ns ns}
                  ast)))

(defn analyze-form
  ([form]
   (analyze-form form {}))
  ([form {:keys [locals ns source-file]}]
   (let [target-ns (or (some-> ns the-ns) *ns*)
         env (binding [*ns* target-ns]
               (cond-> (assoc (ta/empty-env)
                              :ns (ns-name target-ns)
                              :locals (into {}
                                            (map-indexed (fn [idx sym]
                                                           [sym (ac/local-binding-ast idx sym)]))
                                            (keys locals)))
                 source-file
                 (assoc :file source-file)))]
     (binding [*ns* target-ns]
       (ana.jvm/analyze form env)))))

(defn annotate-form-loop
  ([dict form]
   (annotate-form-loop dict form {}))
  ([dict form opts]
   (annotate-ast (merge anf/native-fn-dict dict)
                 (analyze-form form opts)
                 opts)))
