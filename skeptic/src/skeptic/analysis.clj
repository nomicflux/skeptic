(ns skeptic.analysis
  (:require [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [schema.core :as s]
            [skeptic.analysis.resolvers :as ar]
            [skeptic.analysis.schema :as as]))

(defn fn-schema?
  [schema]
  (try
    (boolean (:input-schemas (into {} schema)))
    (catch Exception _e
      false)))

(defn one->arg-entry
  [idx one]
  (let [m (try (into {} one)
               (catch Exception _e {}))]
    {:schema (or (:schema m) s/Any)
     :optional? false
     :name (or (:name m) (symbol (str "arg" idx)))}))

(defn schema->callable
  [schema]
  (when (fn-schema? schema)
    (let [{:keys [input-schemas output-schema]} (into {} schema)]
      {:output output-schema
       :arglists (into {}
                       (map (fn [inputs]
                              [(count inputs)
                               {:arglist (mapv (fn [idx one]
                                                 (:name (one->arg-entry idx one)))
                                               (range)
                                               inputs)
                                :count (count inputs)
                                :schema (mapv one->arg-entry (range) inputs)}]))
                       input-schemas)})))

(defn entry-map?
  [entry]
  (and (map? entry)
       (or (contains? entry :schema)
           (contains? entry :output)
           (contains? entry :arglists)
           (contains? entry :name))))

(defn normalize-entry
  [entry]
  (when (some? entry)
    (let [base (if (entry-map? entry)
                 entry
                 {:schema entry})]
      (merge (schema->callable (:schema base))
             base))))

(defn class->schema
  [klass]
  (cond
    (or (= klass java.lang.Long)
        (= klass Long/TYPE)
        (= klass java.lang.Integer)
        (= klass Integer/TYPE)
        (= klass java.lang.Short)
        (= klass Short/TYPE)
        (= klass java.lang.Byte)
        (= klass Byte/TYPE)
        (= klass java.math.BigInteger))
    s/Int

    (or (= klass java.lang.String)
        (= klass clojure.lang.Keyword)
        (= klass java.lang.Boolean)
        (= klass Boolean/TYPE))
    (cond
      (= klass java.lang.String) s/Str
      (= klass clojure.lang.Keyword) s/Keyword
      :else s/Bool)

    :else
    klass))

(declare schema-of-value)

(defn coll-element-schema
  [values]
  (if (seq values)
    (as/schema-join (set (map schema-of-value values)))
    s/Any))

(defn map-schema
  [m]
  (into {}
        (map (fn [[k v]]
               [(as/valued-schema (schema-of-value k) k)
                (as/valued-schema (schema-of-value v) v)]))
        m))

(defn schema-of-value
  [value]
  (cond
    (nil? value) (s/maybe s/Any)
    (integer? value) s/Int
    (string? value) s/Str
    (keyword? value) s/Keyword
    (boolean? value) s/Bool
    (vector? value) [(coll-element-schema value)]
    (or (list? value) (seq? value)) [(coll-element-schema value)]
    (set? value) #{(coll-element-schema value)}
    (map? value) (map-schema value)
    (class? value) java.lang.Class
    :else (class value)))

(defn schema-join*
  [schemas]
  (let [schemas (vec (remove nil? schemas))
        non-bottom (vec (remove #(= % as/Bottom) schemas))]
    (cond
      (seq non-bottom) (as/schema-join (set non-bottom))
      (seq schemas) as/Bottom
      :else s/Any)))

(defn node-info
  [node]
  (select-keys node [:schema :output :arglists :arglist]))

(defn local-binding-ast
  [idx sym]
  {:op :binding
   :name sym
   :form sym
   :local :arg
   :arg-id idx})

(defn var->sym
  [var]
  (when (instance? clojure.lang.Var var)
    (let [m (meta var)]
      (symbol (str (ns-name (:ns m)) "/" (:name m))))))

(defn lookup-entry
  [dict node name]
  (let [candidates (remove nil?
                           [name
                            (:form node)
                            (var->sym (:var node))])]
    (some (comp normalize-entry dict) candidates)))

(defn default-call-info
  [arity]
  {:expected-arglist (vec (repeat arity s/Any))
   :output s/Any
   :fn-schema (as/dynamic-fn-schema arity s/Any)})

(defn call-info
  [fn-node args]
  (if (:arglists fn-node)
    (let [converted (ar/convert-arglists args
                                         {:arglists (:arglists fn-node)
                                          :output (:output fn-node)})]
      {:expected-arglist (:arglist converted)
       :output (:output converted)
       :fn-schema (:schema converted)})
    (default-call-info (count args))))

(declare annotate-node)

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
  (assoc node :schema (schema-of-value (:val node))))

(defn annotate-binding
  [ctx node]
  (if-let [init (:init node)]
    (let [annotated-init (annotate-node ctx init)]
      (merge node
             {:init annotated-init}
             (node-info annotated-init)))
    node))

(defn annotate-local
  [{:keys [locals]} node]
  (merge node
         (or (normalize-entry (get locals (:form node)))
             {:schema s/Any})))

(defn annotate-var-like
  [{:keys [dict name]} node]
  (merge node
         (or (lookup-entry dict node name)
             {:schema s/Any})))

(defn annotate-static-call
  [ctx node]
  (let [args (mapv #(annotate-node ctx %) (:args node))]
    (assoc node
           :args args
           :actual-arglist (mapv :schema args)
           :expected-arglist (vec (repeat (count args) s/Any))
           :schema s/Any)))

(defn annotate-invoke
  [ctx node]
  (let [fn-node (annotate-node ctx (:fn node))
        args (mapv #(annotate-node ctx %) (:args node))
        {:keys [expected-arglist output fn-schema]} (call-info fn-node args)]
    (assoc node
           :fn fn-node
           :args args
           :actual-arglist (mapv :schema args)
           :expected-arglist expected-arglist
           :schema output
           :fn-schema fn-schema)))

(defn annotate-do
  [ctx node]
  (let [statements (mapv #(annotate-node ctx %) (:statements node))
        ret (annotate-node ctx (:ret node))]
    (assoc node
           :statements statements
           :ret ret
           :schema (:schema ret))))

(defn annotate-let
  [{:keys [locals] :as ctx} node]
  (let [[bindings final-locals]
        (reduce (fn [[acc env] binding]
                  (let [annotated (annotate-binding (assoc ctx :locals env) binding)
                        env-entry (or (node-info annotated) {:schema s/Any})]
                    [(conj acc annotated)
                     (assoc env (:form binding) env-entry)]))
                [[] locals]
                (:bindings node))
        body (annotate-node (assoc ctx :locals final-locals) (:body node))]
    (assoc node
           :bindings bindings
           :body body
           :schema (:schema body))))

(defn truthy-then-locals
  [locals test-node]
  (if (= :local (:op test-node))
    (update locals
            (:form test-node)
            (fn [entry]
              (when entry
                (update (normalize-entry entry) :schema as/de-maybe))))
    locals))

(defn annotate-if
  [{:keys [locals] :as ctx} node]
  (let [test-node (annotate-node ctx (:test node))
        then-node (annotate-node (assoc ctx :locals (truthy-then-locals locals test-node))
                                 (:then node))
        else-node (annotate-node ctx (:else node))]
    (assoc node
           :test test-node
           :then then-node
           :else else-node
           :schema (schema-join* [(:schema then-node) (:schema else-node)]))))

(defn arg-schema-specs
  [dict name params]
  (let [entry (when-some [sym name]
                (get dict sym))
        arg-specs (get-in entry [:arglists (count params) :schema])]
    (or arg-specs
        (mapv (fn [param]
                {:schema s/Any
                 :optional? false
                 :name (:form param)})
              params))))

(defn annotate-fn-method
  [{:keys [locals dict name] :as ctx} node]
  (let [param-specs (arg-schema-specs dict name (:params node))
        annotated-params (mapv (fn [param spec]
                                 (merge param spec))
                               (:params node)
                               param-specs)
        param-locals (into locals
                           (map (fn [param]
                                  [(:form param) (node-info param)]))
                           annotated-params)
        body (annotate-node (assoc ctx :locals param-locals)
                            (:body node))]
    (assoc node
           :params annotated-params
           :body body
           :schema (:schema body)
           :output (:schema body)
           :arglist (mapv :name param-specs)
           :arg-schema param-specs)))

(defn method->arglist-entry
  [method]
  {:arglist (:arglist method)
   :count (count (:arg-schema method))
   :schema (mapv (fn [{:keys [schema name]}]
                   {:schema schema
                    :optional? false
                    :name name})
                 (:arg-schema method))})

(defn annotate-fn
  [ctx node]
  (let [methods (mapv #(annotate-fn-method ctx %) (:methods node))
        arglists (into {}
                       (map (fn [method]
                              [(count (:arg-schema method))
                               (method->arglist-entry method)]))
                       methods)
        output (schema-join* (map :output methods))]
    (assoc node
           :methods methods
           :output output
           :arglists arglists
           :schema (s/make-fn-schema output
                                     (mapv (fn [method]
                                             (mapv (fn [{:keys [schema name]}]
                                                     (s/one schema name))
                                                   (:arg-schema method)))
                                           methods)))))

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
                   :schema (as/variable (or (:schema init-node) s/Any)))
      meta-node (assoc :meta meta-node)
      init-node (assoc :init init-node))))

(defn annotate-vector
  [ctx node]
  (let [items (mapv #(annotate-node ctx %) (:items node))]
    (assoc node
           :items items
           :schema [(if (seq items)
                      (schema-join* (map :schema items))
                      s/Any)])))

(defn annotate-set
  [ctx node]
  (let [items (mapv #(annotate-node ctx %) (:items node))]
    (assoc node
           :items items
           :schema #{(if (seq items)
                       (schema-join* (map :schema items))
                       s/Any)})))

(defn annotate-map
  [ctx node]
  (let [keys (mapv #(annotate-node ctx %) (:keys node))
        vals (mapv #(annotate-node ctx %) (:vals node))]
    (assoc node
           :keys keys
           :vals vals
           :schema (into {}
                         (map (fn [k v]
                                [(as/valued-schema (:schema k) (:form k))
                                 (as/valued-schema (:schema v) (:form v))])
                              keys
                              vals)))))

(defn annotate-new
  [ctx node]
  (let [class-node (annotate-node ctx (:class node))
        args (mapv #(annotate-node ctx %) (:args node))]
    (assoc node
           :class class-node
           :args args
           :schema (or (:val class-node) s/Any))))

(defn annotate-with-meta
  [ctx node]
  (let [meta-node (annotate-node ctx (:meta node))
        expr-node (annotate-node ctx (:expr node))]
    (merge node
           {:meta meta-node
            :expr expr-node}
           (node-info expr-node))))

(defn annotate-throw
  [ctx node]
  (let [exception (annotate-node ctx (:exception node))]
    (assoc node
           :exception exception
           :schema as/Bottom)))

(defn annotate-catch
  [{:keys [locals] :as ctx} node]
  (let [class-node (annotate-node ctx (:class node))
        caught-schema (or (:val class-node) s/Any)
        local-node (merge (:local node) {:schema caught-schema})
        body (annotate-node (assoc ctx
                                   :locals (assoc locals (:form (:local node))
                                                  {:schema caught-schema}))
                            (:body node))]
    (assoc node
           :class class-node
           :local local-node
           :body body
           :schema (:schema body))))

(defn annotate-try
  [ctx node]
  (let [body (annotate-node ctx (:body node))
        catches (mapv #(annotate-catch ctx %) (:catches node))
        finally-node (when-some [finally-node (:finally node)]
                       (annotate-node ctx finally-node))]
    (cond-> (assoc node
                   :body body
                   :catches catches
                   :schema (schema-join* (cons (:schema body)
                                               (map :schema catches))))
      finally-node (assoc :finally finally-node))))

(defn annotate-quote
  [ctx node]
  (let [expr (annotate-node ctx (:expr node))]
    (assoc node
           :expr expr
           :schema (schema-of-value (-> node :form second)))))

(defn annotate-node
  [ctx node]
  (case (:op node)
    :binding (annotate-binding ctx node)
    :const (annotate-const ctx node)
    :def (annotate-def ctx node)
    :do (annotate-do ctx node)
    :fn (annotate-fn ctx node)
    :fn-method (annotate-fn-method ctx node)
    :if (annotate-if ctx node)
    :invoke (annotate-invoke ctx node)
    :let (annotate-let ctx node)
    :local (annotate-local ctx node)
    :map (annotate-map ctx node)
    :new (annotate-new ctx node)
    :quote (annotate-quote ctx node)
    :set (annotate-set ctx node)
    :static-call (annotate-static-call ctx node)
    :the-var (annotate-var-like ctx node)
    :throw (annotate-throw ctx node)
    :try (annotate-try ctx node)
    :var (annotate-var-like ctx node)
    :vector (annotate-vector ctx node)
    :with-meta (annotate-with-meta ctx node)
    (assoc (annotate-children ctx node)
           :schema s/Any)))

(defn annotate-ast
  ([dict ast]
   (annotate-ast dict ast {}))
  ([dict ast {:keys [locals name]}]
   (annotate-node {:dict dict
                   :locals (into {}
                                 (map (fn [[sym entry]]
                                        [sym (normalize-entry entry)]))
                                 locals)
                   :name name}
                  ast)))

(defn analyze-form
  ([form]
   (analyze-form form {}))
  ([form {:keys [locals ns]}]
   (let [target-ns (or (some-> ns the-ns) *ns*)
         env (binding [*ns* target-ns]
               (assoc (ta/empty-env)
                      :ns (ns-name target-ns)
                      :locals (into {}
                                    (map-indexed (fn [idx sym]
                                                   [sym (local-binding-ast idx sym)]))
                                    (keys locals))))]
     (binding [*ns* target-ns]
       (ana.jvm/analyze form env)))))

(defn attach-schema-info-loop
  ([dict form]
   (attach-schema-info-loop dict form {}))
  ([dict form opts]
   (annotate-ast dict
                 (analyze-form form opts)
                 opts)))
