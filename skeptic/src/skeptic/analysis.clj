(ns skeptic.analysis
  (:require [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [schema.core :as s]
            [skeptic.analysis.resolvers :as ar]
            [skeptic.analysis.schema :as as])
  (:import [schema.core FnSchema]))

(defn fn-schema?
  [schema]
  (instance? FnSchema schema))

(declare class->schema)

(defn one->arg-entry
  [idx one]
  (let [m (try (into {} one)
               (catch Exception _e {}))]
    {:schema (as/canonicalize-schema (or (:schema m) s/Any))
     :optional? false
     :name (or (:name m) (symbol (str "arg" idx)))}))

(defn schema->callable
  [schema]
  (when (fn-schema? schema)
    (let [{:keys [input-schemas output-schema]} (into {} schema)]
      (as/canonicalize-entry
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
                        input-schemas)}))))

(defn entry-map?
  [entry]
  (and (map? entry)
       (or (contains? entry :schema)
           (contains? entry :output)
           (contains? entry :arglists))))

(defn normalize-entry
  [entry]
  (when (some? entry)
    (let [base (if (entry-map? entry)
                 entry
                 {:schema entry})]
      (as/strip-derived-types
       (as/canonicalize-entry
        (merge (schema->callable (:schema base))
               (cond-> base
                 (:schema base) (update :schema as/canonicalize-schema)
                 (:output base) (update :output as/canonicalize-schema))))))))

(defn class->schema
  [klass]
  (as/canonical-scalar-schema klass))

(declare schema-of-value)

(defn coll-element-schema
  [values]
  (if (seq values)
    (as/schema-join (set (map schema-of-value values)))
    s/Any))

(defn map-schema
  [m]
  (as/canonicalize-schema
   (into {}
         (map (fn [[k v]]
                [(as/valued-schema (schema-of-value k) k)
                 (as/valued-schema (schema-of-value v) v)]))
         m)))

(defn schema-of-value
  [value]
  (cond
    (nil? value) (s/maybe s/Any)
    (integer? value) s/Int
    (string? value) s/Str
    (keyword? value) s/Keyword
    (symbol? value) s/Symbol
    (boolean? value) s/Bool
    (vector? value) [(coll-element-schema value)]
    (or (list? value) (seq? value)) [(coll-element-schema value)]
    (set? value) #{(coll-element-schema value)}
    (map? value) (map-schema value)
    (class? value) java.lang.Class
    :else (class->schema (class value))))

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
  (select-keys node [:schema :output :arglists :arglist :expected-arglist :actual-arglist :fn-schema :origin]))

(defn literal-map-key?
  [node]
  (contains? #{:const :quote} (:op node)))

(defn semantic-map-key
  [node]
  (as/canonicalize-schema
   (if (literal-map-key? node)
     (:form node)
     (:schema node))))

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

(defn qualify-symbol
  [ns-sym sym]
  (cond
    (nil? sym) nil
    (not (symbol? sym)) sym
    (namespace sym) sym
    ns-sym (symbol (str ns-sym "/" sym))
    :else sym))

(defn lookup-entry
  [dict ns-sym node]
  (let [candidates (remove nil?
                           [(:form node)
                            (qualify-symbol ns-sym (:form node))
                            (var->sym (:var node))])]
    (some (comp normalize-entry dict) candidates)))

(defn default-call-info
  [arity output]
  {:expected-arglist (vec (repeat arity s/Any))
   :output (or output s/Any)
   :fn-schema (as/dynamic-fn-schema arity (or output s/Any))})

(defn typed-arglist-entry?
  [{:keys [schema]}]
  (boolean (seq schema)))

(defn typed-callable?
  [fn-node]
  (and (:arglists fn-node)
       (some typed-arglist-entry?
             (vals (:arglists fn-node)))))

(defn merge-call?
  [fn-node]
  (let [resolved (or (var->sym (:var fn-node))
                     (:form fn-node))]
    (contains? #{'clojure.core/merge 'merge} resolved)))

(defn contains-call?
  [fn-node]
  (let [resolved (or (var->sym (:var fn-node))
                     (:form fn-node))]
    (contains? #{'clojure.core/contains? 'contains? 'contains} resolved)))

(defn get-call?
  [fn-node]
  (let [resolved (or (var->sym (:var fn-node))
                     (:form fn-node))]
    (contains? #{'clojure.core/get 'get} resolved)))

(defn static-get-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/get 'get} (:method node))))

(defn static-merge-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/merge 'merge} (:method node))))

(defn static-contains-call?
  [node]
  (and (= clojure.lang.RT (:class node))
       (contains? #{'clojure.core/contains? 'contains? 'contains} (:method node))))

(defn call-info
  [fn-node args]
  (if (typed-callable? fn-node)
    (let [converted (ar/convert-arglists args
                                         {:arglists (:arglists fn-node)
                                          :output (or (:output fn-node) s/Any)})]
      {:expected-arglist (:arglist converted)
       :output (or (:output converted) s/Any)
       :fn-schema (:schema converted)})
    (default-call-info (count args) (:output fn-node))))

(declare annotate-node
         node-origin
         effective-entry)

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
  (assoc node :schema (as/canonicalize-schema (schema-of-value (:val node)))))

(defn root-origin
  [sym schema]
  {:kind :root
   :sym sym
   :schema (as/canonicalize-schema schema)})

(defn opaque-origin
  [schema]
  {:kind :opaque
   :schema (as/canonicalize-schema schema)})

(defn entry-origin
  [sym entry]
  (or (:origin entry)
      (when-let [schema (:schema entry)]
        (root-origin sym schema))))

(defn node-origin
  [node]
  (or (:origin node)
      (when-let [schema (:schema node)]
        (opaque-origin schema))))

(defn opposite-polarity
  [assumption]
  (update assumption :polarity not))

(defn same-assumption?
  [left right]
  (and (= (:kind left) (:kind right))
       (= (get-in left [:root :sym]) (get-in right [:root :sym]))
       (= (:key left) (:key right))
       (= (:polarity left) (:polarity right))))

(defn opposite-assumption?
  [left right]
  (same-assumption? left (opposite-polarity right)))

(defn assumption-root?
  [assumption root]
  (= (get-in assumption [:root :sym]) (:sym root)))

(defn apply-assumption-to-root-schema
  [schema assumption]
  (case (:kind assumption)
    :truthy-local
    (if (:polarity assumption)
      (as/de-maybe schema)
      schema)

    :contains-key
    (as/refine-schema-by-contains-key schema (:key assumption) (:polarity assumption))

    schema))

(defn refine-root-schema
  [root assumptions]
  (reduce (fn [schema assumption]
            (if (assumption-root? assumption root)
              (apply-assumption-to-root-schema schema assumption)
              schema))
          (:schema root)
          assumptions))

(defn assumption-base-schema
  [assumption assumptions]
  (let [same-proposition? (fn [candidate]
                            (and (= (:kind candidate) (:kind assumption))
                                 (= (get-in candidate [:root :sym]) (get-in assumption [:root :sym]))
                                 (= (:key candidate) (:key assumption))))]
    (refine-root-schema (:root assumption)
                        (remove same-proposition? assumptions))))

(defn assumption-truth
  [assumption assumptions]
  (cond
    (some #(same-assumption? assumption %) assumptions) :true
    (some #(opposite-assumption? assumption %) assumptions) :false

    :else
    (case (:kind assumption)
      :contains-key
      (case (as/contains-key-classification (assumption-base-schema assumption assumptions)
                                            (:key assumption))
        :always (if (:polarity assumption) :true :false)
        :never (if (:polarity assumption) :false :true)
        :unknown :unknown)

      :truthy-local
      :unknown

      :unknown)))

(defn origin-schema
  [origin assumptions]
  (case (:kind origin)
    :root (refine-root-schema origin assumptions)
    :opaque (:schema origin)
    :branch (case (assumption-truth (:test origin) assumptions)
              :true (origin-schema (:then-origin origin) assumptions)
              :false (origin-schema (:else-origin origin) assumptions)
              (schema-join* [(origin-schema (:then-origin origin) assumptions)
                             (origin-schema (:else-origin origin) assumptions)]))
    (:schema origin)))

(defn effective-entry
  [sym entry assumptions]
  (let [entry (normalize-entry entry)
        origin (entry-origin sym entry)
        schema (or (some-> origin (origin-schema assumptions))
                   (:schema entry)
                   s/Any)]
    (cond-> (or entry {:schema s/Any})
      true (assoc :schema (as/canonicalize-schema schema))
      origin (assoc :origin origin))))

(defn local-root-origin
  [node]
  (let [origin (node-origin node)]
    (when (= :root (:kind origin))
      origin)))

(defn contains-key-test-assumption
  [target-node key]
  (when-let [root (local-root-origin target-node)]
    {:kind :contains-key
     :root root
     :key key
     :polarity true}))

(defn test->assumption
  [test-node]
  (cond
    (= :local (:op test-node))
    (when-let [root (local-root-origin test-node)]
      {:kind :truthy-local
       :root root
       :polarity true})

    (and (= :invoke (:op test-node))
         (contains-call? (:fn test-node)))
    (let [[target-node key-node] (:args test-node)]
      (when (keyword? (:form key-node))
        (contains-key-test-assumption target-node (:form key-node))))

    (and (= :static-call (:op test-node))
         (static-contains-call? test-node))
    (let [[target-node key-node] (:args test-node)]
      (when (keyword? (:form key-node))
        (contains-key-test-assumption target-node (:form key-node))))

    :else
    nil))

(defn refine-locals-for-assumption
  [locals assumptions]
  (into {}
        (map (fn [[sym entry]]
               [sym (effective-entry sym entry assumptions)]))
        locals))

(defn branch-local-envs
  [locals assumptions assumption]
  (let [then-assumptions (cond-> (vec assumptions)
                           assumption (conj assumption))
        else-assumption (some-> assumption opposite-polarity)
        else-assumptions (cond-> (vec assumptions)
                           else-assumption (conj else-assumption))]
    {:then-locals (refine-locals-for-assumption locals then-assumptions)
     :then-assumptions then-assumptions
     :else-locals (refine-locals-for-assumption locals else-assumptions)
     :else-assumptions else-assumptions}))

(defn annotate-binding
  [ctx node]
  (if-let [init (:init node)]
    (let [annotated-init (annotate-node ctx init)]
      (merge node
             {:init annotated-init}
             (node-info annotated-init)))
    node))

(defn annotate-local
  [{:keys [locals assumptions]} node]
  (merge node
         (if-let [entry (get locals (:form node))]
           (effective-entry (:form node) entry assumptions)
           {:schema s/Any})))

(defn annotate-var-like
  [{:keys [dict ns]} node]
  (merge node
         (or (lookup-entry dict ns node)
             {:schema s/Any})))

(defn annotate-static-call
  [ctx node]
  (let [args (mapv #(annotate-node ctx %) (:args node))]
    (assoc node
           :args args
           :actual-arglist (mapv :schema args)
           :expected-arglist (vec (repeat (count args) s/Any))
           :schema (as/canonicalize-schema
                    (cond
                      (static-get-call? node)
                      (let [[target key-node default-node] args
                            key-schema (as/valued-schema (:schema key-node) (:form key-node))]
                        (if default-node
                          (as/map-get-schema (:schema target)
                                             key-schema
                                             (:schema default-node))
                          (as/map-get-schema (:schema target)
                                             key-schema)))

                      (static-merge-call? node)
                      (as/merge-map-schemas (map :schema args))

                      (static-contains-call? node)
                      s/Bool

                      :else
                      s/Any)))))

(defn annotate-invoke
  [ctx node]
  (let [fn-node (annotate-node ctx (:fn node))
        args (mapv #(annotate-node ctx %) (:args node))
        {:keys [expected-arglist output fn-schema]} (call-info fn-node args)
        output (cond
                 (get-call? fn-node)
                 (let [[target key-node default-node] args
                       key-schema (as/valued-schema (:schema key-node) (:form key-node))]
                   (if default-node
                     (as/map-get-schema (:schema target)
                                        key-schema
                                        (:schema default-node))
                     (as/map-get-schema (:schema target)
                                        key-schema)))

                 (merge-call? fn-node)
                 (as/merge-map-schemas (map :schema args))

                 (contains-call? fn-node)
                 s/Bool

                 :else
                 output)]
    (assoc node
           :fn fn-node
           :args args
           :actual-arglist (mapv (comp as/canonicalize-schema :schema) args)
           :expected-arglist (mapv as/canonicalize-schema expected-arglist)
           :schema (as/canonicalize-schema output)
           :fn-schema (as/canonicalize-schema fn-schema))))

(defn annotate-do
  [ctx node]
  (let [statements (mapv #(annotate-node ctx %) (:statements node))
        ret (annotate-node ctx (:ret node))]
    (assoc node
           :statements statements
           :ret ret
           :schema (as/canonicalize-schema (:schema ret)))))

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

(defn annotate-if
  [{:keys [locals assumptions] :as ctx} node]
  (let [test-node (annotate-node ctx (:test node))
        assumption (test->assumption test-node)
        {:keys [then-locals then-assumptions else-locals else-assumptions]}
        (branch-local-envs locals assumptions assumption)
        then-node (annotate-node (assoc ctx
                                        :locals then-locals
                                        :assumptions then-assumptions)
                                 (:then node))
        else-node (annotate-node (assoc ctx
                                        :locals else-locals
                                        :assumptions else-assumptions)
                                 (:else node))
        schema (as/canonicalize-schema
                (schema-join* [(:schema then-node) (:schema else-node)]))
        origin (when assumption
                 {:kind :branch
                  :test assumption
                  :then-origin (node-origin then-node)
                  :else-origin (node-origin else-node)})]
    (assoc node
           :test test-node
           :then then-node
           :else else-node
           :schema schema
           :origin (or origin (opaque-origin schema)))))

(defn arg-schema-specs
  [dict ns-sym name params]
  (let [entry (when-some [sym name]
                (or (get dict sym)
                    (get dict (qualify-symbol ns-sym sym))))
        arg-specs (get-in entry [:arglists (count params) :schema])]
    (or arg-specs
        (mapv (fn [param]
                {:schema s/Any
                 :optional? false
                 :name (:form param)})
              params))))

(defn annotate-fn-method
  [{:keys [locals dict name ns] :as ctx} node]
  (let [param-specs (arg-schema-specs dict ns name (:params node))
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
           :schema (as/canonicalize-schema (:schema body))
           :output (as/canonicalize-schema (:schema body))
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
           :output (as/canonicalize-schema output)
           :arglists arglists
           :schema (as/canonicalize-schema
                    (s/make-fn-schema output
                                      (mapv (fn [method]
                                              (mapv (fn [{:keys [schema name]}]
                                                      (s/one schema name))
                                                    (:arg-schema method)))
                                            methods))))))

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
                   :schema (as/canonicalize-schema
                            (as/variable (or (:schema init-node) s/Any))))
      meta-node (assoc :meta meta-node)
      init-node (assoc :init init-node))))

(defn annotate-vector
  [ctx node]
  (let [items (mapv #(annotate-node ctx %) (:items node))]
    (assoc node
           :items items
           :schema (as/canonicalize-schema
                    [(if (seq items)
                       (schema-join* (map :schema items))
                       s/Any)]))))

(defn annotate-set
  [ctx node]
  (let [items (mapv #(annotate-node ctx %) (:items node))]
    (assoc node
           :items items
           :schema (as/canonicalize-schema
                    #{(if (seq items)
                        (schema-join* (map :schema items))
                        s/Any)}))))

(defn annotate-map
  [ctx node]
  (let [keys (mapv #(annotate-node ctx %) (:keys node))
        vals (mapv #(annotate-node ctx %) (:vals node))]
    (assoc node
           :keys keys
           :vals vals
           :schema (as/canonicalize-schema
                    (into {}
                          (map (fn [k v]
                                 [(semantic-map-key k)
                                  (:schema v)])
                               keys
                               vals))))))

(defn annotate-new
  [ctx node]
  (let [class-node (annotate-node ctx (:class node))
        args (mapv #(annotate-node ctx %) (:args node))]
    (assoc node
           :class class-node
           :args args
           :schema (as/canonicalize-schema (or (:val class-node) s/Any)))))

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
           :schema (as/canonicalize-schema (:schema body)))))

(defn annotate-try
  [ctx node]
  (let [body (annotate-node ctx (:body node))
        catches (mapv #(annotate-catch ctx %) (:catches node))
        finally-node (when-some [finally-node (:finally node)]
                       (annotate-node ctx finally-node))]
    (cond-> (assoc node
                   :body body
                   :catches catches
                   :schema (as/canonicalize-schema
                            (schema-join* (cons (:schema body)
                                                (map :schema catches)))))
      finally-node (assoc :finally finally-node))))

(defn annotate-quote
  [ctx node]
  (let [expr (annotate-node ctx (:expr node))]
    (assoc node
           :expr expr
           :schema (as/canonicalize-schema
                    (schema-of-value (-> node :form second))))))

(defn annotate-node
  [ctx node]
  (as/strip-derived-types
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
            :schema (as/canonicalize-schema s/Any)))))

(defn annotate-ast
  ([dict ast]
   (annotate-ast dict ast {}))
  ([dict ast {:keys [locals name ns assumptions]}]
   (annotate-node {:dict dict
                   :locals (into {}
                                 (map (fn [[sym entry]]
                                        [sym (normalize-entry entry)]))
                                 locals)
                   :assumptions (vec assumptions)
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
                                                           [sym (local-binding-ast idx sym)]))
                                            (keys locals)))
                 source-file
                 (assoc :file source-file)))]
     (binding [*ns* target-ns]
       (ana.jvm/analyze form env)))))

(defn attach-schema-info-loop
  ([dict form]
   (attach-schema-info-loop dict form {}))
  ([dict form opts]
   (annotate-ast dict
                 (analyze-form form opts)
                 opts)))
