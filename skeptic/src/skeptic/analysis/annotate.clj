(ns skeptic.analysis.annotate
  (:require [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.jvm :as ana.jvm]
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
            [skeptic.analysis.value :as av])
  (:import [clojure.lang LazySeq]))

(declare annotate-node)

(def bool-type
  (at/->GroundT :bool 'Bool))

(defn annotate-children
  [ctx node]
  (reduce (fn [acc key]
            (let [value (get acc key)
                  annotated (if (vector? value)
                              (mapv #(annotate-node ctx %) value)
                              (annotate-node ctx value))]
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
    (let [annotated-init (annotate-node ctx init)]
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

(defn- seqish-element-type
  [t]
  (when-some [t (some-> t ato/normalize-type)]
    (cond
      (and (at/vector-type? t) (:homogeneous? t)) (first (:items t))
      (and (at/seq-type? t) (:homogeneous? t)) (first (:items t))
      (at/vector-type? t) (av/type-join* (:items t))
      (at/seq-type? t) (av/type-join* (:items t))
      :else nil)))

(defn- vector-to-homogeneous-seq-type
  [t]
  (when-some [t (some-> t ato/normalize-type)]
    (when (at/vector-type? t)
      (let [elem (if (:homogeneous? t)
                   (first (:items t))
                   (av/type-join* (:items t)))]
        (at/->SeqT [(ato/normalize-type elem)] true)))))

(defn annotate-instance-call
  [ctx node]
  (let [instance (annotate-node ctx (:instance node))
        args (mapv #(annotate-node ctx %) (:args node))
        method (:method node)
        it (:type instance)
        output (when (#{'nth} method)
                 (when (at/vector-type? it)
                   (ato/normalize-type
                    (if (:homogeneous? it)
                      (first (:items it))
                      (av/type-join* (:items it))))))]
    (assoc node
           :instance instance
           :args args
           :type (or output at/Dyn))))

(defn annotate-static-call
  [ctx node]
  (let [args (mapv #(annotate-node ctx %) (:args node))
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
                 (let [[m kn vn] args
                       lk (when (ac/literal-map-key? kn)
                            (ac/literal-node-value kn))]
                   (if (keyword? lk)
                     (amoa/assoc-type (:type m) lk (:type vn))
                     at/Dyn))

                 (and (ac/static-dissoc-call? node)
                      (>= (count args) 2))
                 (let [[m kn] args
                       lk (when (ac/literal-map-key? kn)
                            (ac/literal-node-value kn))]
                   (if (keyword? lk)
                     (amoa/dissoc-type (:type m) lk)
                     at/Dyn))

                 (and (ac/static-update-call? node)
                      (>= (count args) 3))
                 (let [[m kn uf] args
                       lk (when (ac/literal-map-key? kn)
                            (ac/literal-node-value kn))]
                   (if (keyword? lk)
                     (amoa/update-type (:type m) lk (:type uf))
                     at/Dyn))

                 (ac/static-contains-call? node)
                 bool-type

                 native-info
                 (:output-type native-info)

                 :else
                 at/Dyn)]
    (assoc node
           :args args
           :actual-argtypes actual-argtypes
           :expected-argtypes expected-argtypes
           :type type)))

(defn annotate-invoke
  [ctx node]
  (let [fn-node (annotate-node ctx (:fn node))
        args (mapv #(annotate-node ctx %) (:args node))
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
                      (let [[m kn vn] args
                            lk (when (ac/literal-map-key? kn)
                                 (ac/literal-node-value kn))]
                        (if (keyword? lk)
                          (amoa/assoc-type (:type m) lk (:type vn))
                          output-type))

                      (and (ac/dissoc-call? fn-node)
                           (>= (count args) 2))
                      (let [[m kn] args
                            lk (when (ac/literal-map-key? kn)
                                 (ac/literal-node-value kn))]
                        (if (keyword? lk)
                          (amoa/dissoc-type (:type m) lk)
                          output-type))

                      (and (ac/update-call? fn-node)
                           (>= (count args) 3))
                      (let [[m kn uf] args
                            lk (when (ac/literal-map-key? kn)
                                 (ac/literal-node-value kn))]
                        (if (keyword? lk)
                          (amoa/update-type (:type m) lk (:type uf))
                          output-type))

                      (ac/contains-call? fn-node)
                      bool-type

                      :else
                      output-type)]
    (assoc node
           :fn fn-node
           :args args
           :actual-argtypes (mapv :type args)
           :expected-argtypes (mapv ato/normalize-type expected-argtypes)
           :type (ato/normalize-type output-type)
           :fn-type (ato/normalize-type fn-type))))

(defn annotate-do
  [ctx node]
  (let [statements (mapv #(annotate-node ctx %) (:statements node))
        ret (annotate-node ctx (:ret node))]
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
                        env-entry (if (and (= :local (:op init))
                                           (= :root (:kind (ao/node-origin init))))
                                    (assoc base-entry :origin (ao/root-origin (:form init)
                                                                             (:type init)))
                                    base-entry)]
                    [(conj acc annotated)
                     (assoc env (:form binding) env-entry)]))
                [[] locals]
                (:bindings node))
        body (annotate-node (assoc ctx :locals final-locals) (:body node))]
    (assoc node
           :bindings bindings
           :body body
           :type (:type body))))

(defn- binding-recur-target-types
  [bindings]
  (mapv #(or (:type %) at/Dyn) bindings))

(defn annotate-loop
  [{:keys [locals recur-targets] :as ctx} node]
  (let [loop-id (:loop-id node)
        [bindings final-locals]
        (reduce (fn [[acc env] binding]
                  (let [annotated (annotate-binding (assoc ctx :locals env) binding)
                        init (:init annotated)
                        base-entry (or (ac/node-info annotated) {:type at/Dyn})
                        env-entry (if (and (= :local (:op init))
                                           (= :root (:kind (ao/node-origin init))))
                                    (assoc base-entry :origin (ao/root-origin (:form init)
                                                                             (:type init)))
                                    base-entry)]
                    [(conj acc annotated)
                     (assoc env (:form binding) env-entry)]))
                [[] locals]
                (:bindings node))
        targets (binding-recur-target-types bindings)
        recur-ctx (assoc ctx
                         :locals final-locals
                         :recur-targets (cond-> (or recur-targets {})
                                          loop-id (assoc loop-id targets)))
        body (annotate-node recur-ctx (:body node))]
    (assoc node
           :bindings bindings
           :body body
           :type (:type body))))

(defn annotate-recur
  [{:keys [recur-targets] :as ctx} node]
  (let [exprs (mapv #(annotate-node ctx %) (:exprs node))
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
  (let [test-node (annotate-node ctx (:test node))
        assumption (ao/test->assumption test-node)
        {:keys [then-locals then-assumptions else-locals else-assumptions]}
        (ao/branch-local-envs locals assumptions assumption)
        then-node (annotate-node (assoc ctx
                                        :locals then-locals
                                        :assumptions then-assumptions)
                                 (:then node))
        else-node (annotate-node (assoc ctx
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
  (let [test-node (annotate-node ctx (:test node))
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
                      ann (annotate-node (assoc ctx
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
        default-node (annotate-node (assoc ctx
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
  [{:keys [locals dict name ns recur-targets] :as ctx} node]
  (let [param-specs (arg-type-specs dict ns name (:params node))
        annotated-params (mapv (fn [param spec]
                                 (merge param spec))
                               (:params node)
                               param-specs)
        param-locals (into locals
                           (map (fn [param]
                                  [(:form param) (ac/node-info param)]))
                           annotated-params)
        recur-targets (cond-> (or recur-targets {})
                        (:loop-id node)
                        (assoc (:loop-id node) (mapv :type annotated-params)))
        body (annotate-node (assoc ctx
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
  [ctx node]
  (let [methods (mapv #(annotate-fn-method ctx %) (:methods node))
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

(defn annotate-def
  [{:keys [locals] :as ctx} node]
  (let [meta-node (when-some [meta-node (:meta node)]
                    (annotate-node ctx meta-node))
        init-node (when-some [init-node (:init node)]
                    (annotate-node (assoc ctx
                                          :locals locals
                                          :name (:name node))
                                   init-node))]
    (cond-> (assoc node
                   :type (at/->VarT (or (:type init-node) at/Dyn)))
      meta-node (assoc :meta meta-node)
      init-node (assoc :init init-node))))

(defn annotate-vector
  [ctx node]
  (let [items (mapv #(annotate-node ctx %) (:items node))]
    (assoc node
           :items items
           :type (ato/normalize-type
                  (mapv (fn [item]
                          (or (:type item) at/Dyn))
                        items)))))

(defn annotate-set
  [ctx node]
  (let [items (mapv #(annotate-node ctx %) (:items node))]
    (assoc node
           :items items
           :type (ato/normalize-type
                  #{(if (seq items)
                      (av/type-join* (map :type items))
                      at/Dyn)}))))

(defn annotate-map
  [ctx node]
  (let [keys (mapv #(annotate-node ctx %) (:keys node))
        vals (mapv #(annotate-node ctx %) (:vals node))]
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

(defn- for-body-element-type
  [body]
  (->> (sac/ast-nodes body)
       (keep (fn [node]
               (when (= :invoke (:op node))
                 (let [fn-sym (or (ac/var->sym (:var (:fn node)))
                                  (-> node :fn :form))]
                   (when (contains? #{'clojure.core/cons 'cons} fn-sym)
                     (some-> node :args first :type))))))
       first))

(defn- lazy-seq-new-type
  [class-node args]
  (when (and (= :const (:op class-node))
             (= LazySeq (:val class-node)))
    (let [elem (if (and (seq args)
                        (= :fn (:op (first args)))
                        (= 1 (count (:methods (first args))))
                        (empty? (:params (first (:methods (first args))))))
                 (let [body (:body (first (:methods (first args))))
                       t (-> body :type ato/normalize-type)
                       t (if (at/maybe-type? t)
                           (ato/normalize-type (:inner t))
                           t)]
                   (if (ato/unknown-type? t)
                     (or (some-> (for-body-element-type body) ato/normalize-type)
                         at/Dyn)
                     t))
                 at/Dyn)]
      (at/->SeqT [elem] true))))

(defn annotate-new
  [ctx node]
  (let [class-node (annotate-node ctx (:class node))
        args (mapv #(annotate-node ctx %) (:args node))]
    (assoc node
           :class class-node
           :args args
           :type (or (lazy-seq-new-type class-node args)
                 (some-> (:val class-node) av/class->type)
                 at/Dyn))))

(defn annotate-with-meta
  [ctx node]
  (let [meta-node (annotate-node ctx (:meta node))
        expr-node (annotate-node ctx (:expr node))]
    (merge node
           {:meta meta-node
            :expr expr-node}
           (ac/node-info expr-node))))

(defn annotate-throw
  [ctx node]
  (let [exception (annotate-node ctx (:exception node))]
    (assoc node
           :exception exception
           :type at/BottomType)))

(defn annotate-catch
  [{:keys [locals] :as ctx} node]
  (let [class-node (annotate-node ctx (:class node))
        caught-type (or (some-> (:val class-node) av/class->type)
                        at/Dyn)
        local-node (merge (:local node)
                          {:type caught-type})
        body (annotate-node (assoc ctx
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
  (let [body (annotate-node ctx (:body node))
        catches (mapv #(annotate-catch ctx %) (:catches node))
        finally-node (when-some [finally-node (:finally node)]
                       (annotate-node ctx finally-node))]
    (cond-> (assoc node
                   :body body
                   :catches catches
                   :type (av/type-join* (cons (:type body)
                                              (map :type catches))))
      finally-node (assoc :finally finally-node))))

(defn annotate-quote
  [ctx node]
  (let [expr (annotate-node ctx (:expr node))]
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
              :type at/Dyn)))))

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
