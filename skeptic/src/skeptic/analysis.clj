(ns skeptic.analysis
  (:require [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.resolvers :as ar]
            [skeptic.analysis.schema.map-ops :as asm]
            [skeptic.analysis.schema.value-check :as asv]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(declare class->schema
         type-of-value
         schema-of-value)

(defn normalize-declared-type
  [value]
  (when (some? value)
    (if (ab/type-domain-value? value)
      (ab/normalize-type value)
      (ab/import-schema-type value))))

(defn compat-schema
  [type]
  (some-> type abr/type->schema-compat))

(defn compat-schemas
  [types]
  (mapv compat-schema types))

(defn one->arg-entry
  [idx one]
  (let [m (try (into {} one)
               (catch Exception _e {}))]
    (let [type (normalize-declared-type (or (:type m) (:schema m) s/Any))]
      {:type type
       :schema (compat-schema type)
     :optional? false
       :name (or (:name m) (symbol (str "arg" idx)))})))

(defn arg-entry-map?
  [entry]
  (and (map? entry)
       (or (contains? entry :type)
           (contains? entry :schema)
           (contains? entry :optional?)
           (contains? entry :name))))

(defn normalize-arg-entry
  [entry]
  (let [base (if (arg-entry-map? entry) entry {:schema entry})
        type (normalize-declared-type (or (:type base)
                                          (:schema base)
                                          s/Any))]
    {:type type
     :schema (compat-schema type)
     :optional? (boolean (:optional? base))
     :name (:name base)}))

(defn normalize-arglist-entry
  [entry]
  (let [types (mapv normalize-arg-entry (or (:types entry)
                                            (:schema entry)
                                            []))]
    (cond-> (-> entry
                (dissoc :types :schema)
                (assoc :types types
                       :schema types))
      (not (contains? entry :count))
      (assoc :count (count types)))))

(defn schema->callable
  [schema]
  (when (sb/fn-schema? schema)
    (let [{:keys [input-schemas output-schema]} (into {} schema)
          output-type (normalize-declared-type output-schema)
          arglists (into {}
                         (map (fn [inputs]
                                (let [types (mapv one->arg-entry (range) inputs)]
                                  [(count inputs)
                                   {:arglist (mapv :name types)
                                    :count (count inputs)
                                    :types types
                                    :schema types}])))
                         input-schemas)
          fn-type (normalize-declared-type schema)]
      {:type fn-type
       :schema (compat-schema fn-type)
       :output-type output-type
       :output (compat-schema output-type)
       :arglists arglists})))

(defn entry-map?
  [entry]
  (and (map? entry)
       (or (contains? entry :type)
           (contains? entry :schema)
           (contains? entry :output-type)
           (contains? entry :output)
           (contains? entry :arglists))))

(defn normalize-entry
  [entry]
  (when (some? entry)
    (let [base (if (entry-map? entry)
                 entry
                 {:schema entry})
          callable (or (when-let [schema (:schema base)]
                         (schema->callable schema))
                       {})
          type (normalize-declared-type (or (:type base)
                                            (:type callable)
                                            (:schema base)
                                            (:schema callable)))
          output-type (normalize-declared-type (or (:output-type base)
                                                   (:output-type callable)
                                                   (:output base)
                                                   (:output callable)))
          arglists (some-> (or (:arglists base)
                               (:arglists callable))
                           ((fn [arglists]
                              (into {}
                                    (map (fn [[k v]]
                                           [k (normalize-arglist-entry v)]))
                                    arglists))))]
       (abr/strip-derived-types
       (cond-> (merge callable
                      (dissoc base :schema :output :type :output-type :arglists)
                      {:type (or type at/Dyn)
                       :schema (compat-schema (or type at/Dyn))})
         output-type (assoc :output-type output-type
                            :output (compat-schema output-type))
         arglists (assoc :arglists arglists))))))

(defn class->schema
  [klass]
  (sb/canonical-scalar-schema klass))

(defn coll-element-schema
  [values]
  (if (seq values)
    (abc/schema-join (set (map schema-of-value values)))
    s/Any))

(defn map-schema
  [m]
  (abc/canonicalize-schema
   (into {}
         (map (fn [[k v]]
                [(sb/valued-schema (schema-of-value k) k)
                 (sb/valued-schema (schema-of-value v) v)]))
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
    (vector? value) (mapv schema-of-value value)
    (or (list? value) (seq? value)) [(coll-element-schema value)]
    (set? value) #{(coll-element-schema value)}
    (map? value) (map-schema value)
    (class? value) java.lang.Class
    :else (class->schema (class value))))

(defn type-of-value
  [value]
  (normalize-declared-type (schema-of-value value)))

(defn type-join*
  [types]
  (let [types (vec (remove nil? (map ab/normalize-type types)))
        non-bottom (vec (remove at/bottom-type? types))]
    (cond
      (seq non-bottom) (ab/union-type non-bottom)
      (seq types) at/BottomType
      :else at/Dyn)))

(defn node-info
  [node]
  (select-keys node [:type :output-type :arglists :arglist :expected-argtypes :actual-argtypes :fn-type
                     :schema :output :expected-arglist :actual-arglist :fn-schema :origin]))

(defn literal-map-key?
  [node]
  (contains? #{:const :quote} (:op node)))

(defn semantic-map-key
  [node]
  (ab/normalize-type
   (if (literal-map-key? node)
     (:form node)
     (:type node))))

(defn get-key-query
  [node]
  (if (literal-map-key? node)
    (asm/exact-key-query (:type node) (:form node) (:form node))
    (asm/domain-key-query (:type node) (:form node))))

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
  (let [output-type (or output at/Dyn)
        expected-argtypes (vec (repeat arity at/Dyn))
        fn-type (normalize-declared-type (sb/dynamic-fn-schema arity s/Any))]
    {:expected-argtypes expected-argtypes
     :expected-arglist (compat-schemas expected-argtypes)
     :output-type output-type
     :output (compat-schema output-type)
     :fn-type fn-type
     :fn-schema (compat-schema fn-type)}))

(defn typed-arglist-entry?
  [{:keys [types]}]
  (boolean (seq types)))

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
                                          :output-type (or (:output-type fn-node) at/Dyn)})]
      {:expected-argtypes (:argtypes converted)
       :expected-arglist (compat-schemas (:argtypes converted))
       :output-type (or (:output-type converted) at/Dyn)
       :output (compat-schema (or (:output-type converted) at/Dyn))
       :fn-type (:type converted)
       :fn-schema (compat-schema (:type converted))})
    (default-call-info (count args) (:output-type fn-node))))

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
  (let [type (type-of-value (:val node))]
    (assoc node
           :type type
           :schema (compat-schema type))))

(defn root-origin
  [sym type]
  {:kind :root
   :sym sym
   :type (ab/normalize-type type)})

(defn opaque-origin
  [type]
  {:kind :opaque
   :type (ab/normalize-type type)})

(defn entry-origin
  [sym entry]
  (or (:origin entry)
      (when-let [type (:type entry)]
        (root-origin sym type))))

(defn node-origin
  [node]
  (or (:origin node)
      (when-let [type (:type node)]
        (opaque-origin type))))

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

(defn apply-assumption-to-root-type
  [type assumption]
  (case (:kind assumption)
    :truthy-local
    (if (:polarity assumption)
      (ab/de-maybe-type type)
      type)

    :contains-key
    (asv/refine-type-by-contains-key type (:key assumption) (:polarity assumption))

    type))

(defn refine-root-type
  [root assumptions]
  (reduce (fn [type assumption]
            (if (assumption-root? assumption root)
              (apply-assumption-to-root-type type assumption)
              type))
          (:type root)
          assumptions))

(defn assumption-base-type
  [assumption assumptions]
  (let [same-proposition? (fn [candidate]
                            (and (= (:kind candidate) (:kind assumption))
                                 (= (get-in candidate [:root :sym]) (get-in assumption [:root :sym]))
                                 (= (:key candidate) (:key assumption))))]
    (refine-root-type (:root assumption)
                      (remove same-proposition? assumptions))))

(defn assumption-truth
  [assumption assumptions]
  (cond
    (some #(same-assumption? assumption %) assumptions) :true
    (some #(opposite-assumption? assumption %) assumptions) :false

    :else
    (case (:kind assumption)
      :contains-key
      (case (asv/contains-key-type-classification (assumption-base-type assumption assumptions)
                                                 (:key assumption))
        :always (if (:polarity assumption) :true :false)
        :never (if (:polarity assumption) :false :true)
        :unknown :unknown)

      :truthy-local
      :unknown

      :unknown)))

(defn origin-type
  [origin assumptions]
  (case (:kind origin)
    :root (refine-root-type origin assumptions)
    :opaque (:type origin)
    :branch (case (assumption-truth (:test origin) assumptions)
              :true (origin-type (:then-origin origin) assumptions)
              :false (origin-type (:else-origin origin) assumptions)
              (type-join* [(origin-type (:then-origin origin) assumptions)
                           (origin-type (:else-origin origin) assumptions)]))
    (:type origin)))

(defn effective-entry
  [sym entry assumptions]
  (let [entry (normalize-entry entry)
        origin (entry-origin sym entry)
        type (or (some-> origin (origin-type assumptions))
                 (:type entry)
                 at/Dyn)]
    (cond-> (or entry {:type at/Dyn
                       :schema (compat-schema at/Dyn)})
      true (assoc :type (ab/normalize-type type)
                  :schema (compat-schema (ab/normalize-type type)))
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
           {:type at/Dyn
            :schema (compat-schema at/Dyn)})))

(defn annotate-var-like
  [{:keys [dict ns]} node]
  (merge node
         (or (lookup-entry dict ns node)
             {:type at/Dyn
              :schema (compat-schema at/Dyn)})))

(defn annotate-static-call
  [ctx node]
  (let [args (mapv #(annotate-node ctx %) (:args node))]
    (let [actual-argtypes (mapv :type args)
          expected-argtypes (vec (repeat (count args) at/Dyn))
          type (cond
                 (static-get-call? node)
                 (let [[target key-node default-node] args
                       key-type (get-key-query key-node)]
                   (if default-node
                     (asm/map-get-type (:type target)
                                      key-type
                                      (:type default-node))
                     (asm/map-get-type (:type target)
                                      key-type)))

                 (static-merge-call? node)
                 (asm/merge-map-types (map :type args))

                 (static-contains-call? node)
                 (normalize-declared-type s/Bool)

                 :else
                 at/Dyn)]
      (assoc node
             :args args
             :actual-argtypes actual-argtypes
             :actual-arglist (compat-schemas actual-argtypes)
             :expected-argtypes expected-argtypes
             :expected-arglist (compat-schemas expected-argtypes)
             :type type
             :schema (compat-schema type)))))

(defn annotate-invoke
  [ctx node]
  (let [fn-node (annotate-node ctx (:fn node))
        args (mapv #(annotate-node ctx %) (:args node))
        {:keys [expected-argtypes output-type fn-type]} (call-info fn-node args)
        output-type (cond
                 (get-call? fn-node)
                 (let [[target key-node default-node] args
                       key-type (get-key-query key-node)]
                   (if default-node
                     (asm/map-get-type (:type target)
                                      key-type
                                      (:type default-node))
                     (asm/map-get-type (:type target)
                                      key-type)))

                 (merge-call? fn-node)
                 (asm/merge-map-types (map :type args))

                 (contains-call? fn-node)
                 (normalize-declared-type s/Bool)

                 :else
                 output-type)]
    (assoc node
           :fn fn-node
           :args args
           :actual-argtypes (mapv :type args)
           :actual-arglist (compat-schemas (mapv :type args))
           :expected-argtypes (mapv ab/normalize-type expected-argtypes)
           :expected-arglist (compat-schemas (mapv ab/normalize-type expected-argtypes))
           :type (ab/normalize-type output-type)
           :schema (compat-schema (ab/normalize-type output-type))
           :fn-type (ab/normalize-type fn-type)
           :fn-schema (compat-schema (ab/normalize-type fn-type)))))

(defn annotate-do
  [ctx node]
  (let [statements (mapv #(annotate-node ctx %) (:statements node))
        ret (annotate-node ctx (:ret node))]
    (assoc node
           :statements statements
           :ret ret
           :type (:type ret)
           :schema (compat-schema (:type ret)))))

(defn annotate-let
  [{:keys [locals] :as ctx} node]
  (let [[bindings final-locals]
        (reduce (fn [[acc env] binding]
                  (let [annotated (annotate-binding (assoc ctx :locals env) binding)
                        env-entry (or (node-info annotated) {:type at/Dyn
                                                             :schema at/Dyn})]
                    [(conj acc annotated)
                    (assoc env (:form binding) env-entry)]))
                [[] locals]
                (:bindings node))
        body (annotate-node (assoc ctx :locals final-locals) (:body node))]
    (assoc node
           :bindings bindings
           :body body
           :type (:type body)
           :schema (compat-schema (:type body)))))

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
        type (type-join* [(:type then-node) (:type else-node)])
        origin (when assumption
                 {:kind :branch
                  :test assumption
                  :then-origin (node-origin then-node)
                  :else-origin (node-origin else-node)})]
    (assoc node
           :test test-node
           :then then-node
           :else else-node
           :type type
           :schema (compat-schema type)
           :origin (or origin (opaque-origin type)))))

(defn arg-schema-specs
  [dict ns-sym name params]
  (let [entry (when-some [sym name]
                (or (get dict sym)
                    (get dict (qualify-symbol ns-sym sym))))
        arg-specs (get-in (normalize-entry entry) [:arglists (count params) :types])]
    (or arg-specs
        (mapv (fn [param]
                {:type at/Dyn
                 :schema (compat-schema at/Dyn)
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
           :type (:type body)
           :schema (compat-schema (:type body))
           :output-type (:type body)
           :output (compat-schema (:type body))
           :arglist (mapv :name param-specs)
           :arg-schema param-specs)))

(defn method->arglist-entry
  [method]
  {:arglist (:arglist method)
   :count (count (:arg-schema method))
   :types (mapv (fn [{:keys [type name]}]
                  {:type type
                   :schema (compat-schema type)
                    :optional? false
                    :name name})
                (:arg-schema method))
   :schema (mapv (fn [{:keys [type name]}]
                   {:type type
                    :schema (compat-schema type)
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
        output-type (type-join* (map :output-type methods))
        fn-type (at/->FunT
                 (mapv (fn [method]
                         (at/->FnMethodT (mapv :type (:arg-schema method))
                                         (:output-type method)
                                         (count (:arg-schema method))
                                         false))
                       methods))]
    (assoc node
           :methods methods
           :output-type output-type
           :output (compat-schema output-type)
           :arglists arglists
           :type fn-type
           :schema (compat-schema fn-type))))

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
                   :type (at/->VarT (or (:type init-node) at/Dyn))
                   :schema (compat-schema (at/->VarT (or (:type init-node) at/Dyn))))
      meta-node (assoc :meta meta-node)
      init-node (assoc :init init-node))))

(defn annotate-vector
  [ctx node]
  (let [items (mapv #(annotate-node ctx %) (:items node))]
    (assoc node
           :items items
           :type (ab/normalize-type
                  (mapv (fn [item]
                          (or (:type item) at/Dyn))
                        items))
           :schema (compat-schema
                    (ab/normalize-type
                     (mapv (fn [item]
                             (or (:type item) at/Dyn))
                           items))))))

(defn annotate-set
  [ctx node]
  (let [items (mapv #(annotate-node ctx %) (:items node))]
    (assoc node
           :items items
           :type (ab/normalize-type
                  #{(if (seq items)
                      (type-join* (map :type items))
                      at/Dyn)})
           :schema (compat-schema
                    (ab/normalize-type
                     #{(if (seq items)
                         (type-join* (map :type items))
                         at/Dyn)})))))

(defn annotate-map
  [ctx node]
  (let [keys (mapv #(annotate-node ctx %) (:keys node))
        vals (mapv #(annotate-node ctx %) (:vals node))]
    (assoc node
           :keys keys
           :vals vals
           :type (ab/normalize-type
                  (into {}
                        (map (fn [k v]
                               [(semantic-map-key k)
                                (:type v)])
                             keys
                             vals)))
           :schema (compat-schema
                    (ab/normalize-type
                     (into {}
                           (map (fn [k v]
                                  [(semantic-map-key k)
                                   (:type v)])
                                keys
                                vals)))))))

(defn annotate-new
  [ctx node]
  (let [class-node (annotate-node ctx (:class node))
        args (mapv #(annotate-node ctx %) (:args node))]
    (assoc node
           :class class-node
           :args args
           :type (normalize-declared-type (or (:val class-node) s/Any))
           :schema (compat-schema (normalize-declared-type (or (:val class-node) s/Any))))))

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
           :type at/BottomType
           :schema (compat-schema at/BottomType))))

(defn annotate-catch
  [{:keys [locals] :as ctx} node]
  (let [class-node (annotate-node ctx (:class node))
        caught-type (normalize-declared-type (or (:val class-node) s/Any))
        local-node (merge (:local node)
                          {:type caught-type
                           :schema (compat-schema caught-type)})
        body (annotate-node (assoc ctx
                                   :locals (assoc locals (:form (:local node))
                                                  {:type caught-type
                                                   :schema (compat-schema caught-type)}))
                            (:body node))]
    (assoc node
           :class class-node
           :local local-node
           :body body
           :type (:type body)
           :schema (compat-schema (:type body)))))

(defn annotate-try
  [ctx node]
  (let [body (annotate-node ctx (:body node))
        catches (mapv #(annotate-catch ctx %) (:catches node))
        finally-node (when-some [finally-node (:finally node)]
                       (annotate-node ctx finally-node))]
    (cond-> (assoc node
                   :body body
                   :catches catches
                   :type (type-join* (cons (:type body)
                                           (map :type catches)))
                   :schema (compat-schema
                            (type-join* (cons (:type body)
                                              (map :type catches)))))
      finally-node (assoc :finally finally-node))))

(defn annotate-quote
  [ctx node]
  (let [expr (annotate-node ctx (:expr node))]
    (assoc node
           :expr expr
           :type (type-of-value (-> node :form second))
           :schema (compat-schema (type-of-value (-> node :form second))))))

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
              :type at/Dyn
              :schema (compat-schema at/Dyn))))))

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
