(ns skeptic.schematize
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.schema :as dschema]
            [schema.core :as s]))

(defn get-fn-schemas*
  [f]
  (->> f
       resolve
       meta
       :schema
       (into {})))

(defmacro get-fn-schemas
  [f]
  `(get-fn-schemas* '~f))


(s/defn get-fn-code :- s/Str
  [{:keys [verbose lookup-failures]}
   func-name :- s/Symbol]
  (if-let [code (repl/source-fn func-name)]
    code
    (do (when lookup-failures
          (when (and verbose (not (contains? @lookup-failures func-name)))
            (println "No code found for" func-name))
          (swap! lookup-failures conj func-name))
        "")))

(s/defn count-map
  [x :- [s/Any]]
  (->> x
       (map (juxt count identity))
       (into {})))

(s/defn arg-list
  [args]
  (reduce
   (fn [{:keys [count args varargs with-varargs]}
        next]
     (cond
       with-varargs
       {:count count
        :args args
        :with-varargs with-varargs
        :varargs (conj varargs next)}

       (str/starts-with? next "&")
       {:count (inc count)
        :args args
        :with-varargs true
        :varargs varargs}

       :else
       {:count (inc count)
        :args (conj args next)
        :with-varargs with-varargs
        :varargs varargs}))
   {:count 0
    :args []
    :with-varargs false
    :varargs []}
   args))

(s/defn arg-map
  [xs :- [s/Any]]
  (reduce
   (fn [acc next]
     (let [{:keys [count args varargs]} (arg-list next)]
       (if (seq varargs)
         (assoc acc :varargs {:args (conj args varargs) :count count})
         (assoc acc count args))))
   {}
   xs))

(defn schema-entry-schema
  [schema]
  (let [m (try (into {} schema)
               (catch Exception _e nil))]
    (or (:schema m) schema)))

(defn normalize-vararg-input-schemas
  [schemas]
  (let [schemas (vec schemas)]
    (if (seq schemas)
      (conj (pop schemas) (schema-entry-schema (peek schemas)))
      schemas)))

(s/defn collect-schemas :- dschema/SchemaDesc
  [{:keys [schema ns name arglists] :as this}]
  (try
    (let [schema (abc/canonicalize-schema schema)]
      (when-not (abc/schema? schema)
        (throw (IllegalArgumentException.
                (format "Invalid Schema annotation for %s/%s: %s"
                        ns
                        name
                        (pr-str schema)))))
      (abc/canonicalize-entry
       (if (or (class? schema) (set? schema) (vector? schema))
         {:name (or (some-> schema abc/schema-display-form pr-str) (str ns "/" name))
          :schema schema
          :output schema
          :arglists {}}
         (let [{:keys [input-schemas output-schema]} (into {} schema)
               inputs (count-map input-schemas)
               args (arg-map arglists)
               args-with-schemas (reduce
                                  (fn [acc next]
                                    (let [input (get inputs next)
                                          arg (get args next)]
                                      (assoc acc
                                             next
                                             (cond-> {:arglist arg}
                                             (= next :varargs)
                                             (assoc :count (:count arg)
                                                    :arglist (:args arg)
                                                    :schema (some-> (get inputs (:count arg))
                                                                    normalize-vararg-input-schemas))

                                             (not (nil? input))
                                             (assoc :schema input)))))
                                  {}
                                  (keys args))]
           {:name (str ns "/" name)
            :schema schema
            :output (or output-schema schema)
            :arglists args-with-schemas}))))
    (catch Exception e
      (println "Exception collecting schemas:" (pr-str this))
      (throw e))))

(s/defn fully-qualify-str :- s/Symbol
  [f :- s/Str]
  (-> f
      symbol
      resolve
      symbol))

(defn dynamic-arg-entry
  [arg]
  {:schema s/Any
   :optional? false
   :name arg})

(defn dynamic-arglists
  [arglists]
  (->> arglists
       arg-map
       (map (fn [[k {:keys [args count] :as arglist}]]
              [k (cond-> {:arglist (or args arglist)}
                    (= k :varargs)
                    (assoc :count count
                           :arglist args
                           :schema (vec (concat (map dynamic-arg-entry (butlast args))
                                                [s/Any])))

                    (not= k :varargs)
                    (assoc :schema (mapv dynamic-arg-entry arglist)))]))
       (into {})))

(defn dynamic-desc
  [v]
  (let [m (meta v)
        qualified-sym (sb/qualified-var-symbol v)
        arglists (when (and (:arglists m)
                            (not (:macro m)))
                   (dynamic-arglists (:arglists m)))]
    (abc/canonicalize-entry
     {:name (str qualified-sym)
      :schema s/Any
      :output s/Any
      :arglists (or arglists {})})))

(defn var-schema-desc
  [v]
  (let [{:keys [schema ns name arglists macro]} (meta v)]
    (when (not macro)
      (if schema
        (collect-schemas {:schema schema
                          :ns (ns-name ns)
                          :name name
                          :arglists arglists})
        (dynamic-desc v)))))

(defn ns-schemas
  [opts ns]
  (binding [*ns* (the-ns ns)]
    (->> ns
         symbol
         ns-interns
         vals
         (keep (fn [v]
                 (when-let [qualified-sym (sb/qualified-var-symbol v)]
                   [qualified-sym (var-schema-desc v)])))
         (into {}))))

(defn raw-arg-name
  [idx arg]
  (cond
    (symbol? arg) arg
    (and (sequential? arg)
         (= 1 (count arg))
         (symbol? (first arg)))
    (first arg)
    :else
    (symbol (str "arg" idx))))

(defn one->typed-arg-entry
  [idx one]
  (let [m (if (map? one)
            one
            (try (into {} one)
                 (catch Exception _e {})))
        raw-type (or (:schema m) one s/Any)]
    {:name (or (:name m) (raw-arg-name idx nil))
     :optional? (boolean (:optional? m))
     :type (ab/schema->type raw-type)}))

(defn arglist->typed-entry
  [entry]
  (let [schemas (vec (or (:schema entry) []))
        arglist (:arglist entry)
        types (mapv (fn [idx schema]
                      (let [typed-entry (one->typed-arg-entry idx schema)
                            m (if (map? schema)
                                schema
                                (try (into {} schema)
                                     (catch Exception _e {})))
                            arg-name (or (:name m)
                                         (raw-arg-name idx (nth arglist idx nil)))]
                        (assoc typed-entry :name arg-name)))
                    (range)
                    schemas)]
    (cond-> {:arglist arglist
             :types types}
      (contains? entry :count)
      (assoc :count (:count entry))

      (not (contains? entry :count))
      (assoc :count (count types)))))

(defn fn-schema->typed-entry
  [{:keys [name schema output arglists]}]
  (cond-> {:name name
           :type (ab/schema->type schema)}
    (some? output)
    (assoc :output-type (ab/schema->type output))

    (seq arglists)
    (assoc :arglists (into {}
                           (map (fn [[k v]]
                                  [k (arglist->typed-entry v)]))
                           arglists))))

(defn schema-desc->typed-entry
  [{:keys [name schema arglists] :as desc}]
  (when desc
    (if (or (sb/fn-schema? schema)
            (seq arglists))
      (fn-schema->typed-entry desc)
      {:name name
       :type (ab/schema->type schema)})))

(defn typed-ns-schemas
  [opts ns]
  (binding [*ns* (the-ns ns)]
    (->> ns
         symbol
         ns-interns
         vals
         (keep (fn [v]
                 (when-let [qualified-sym (sb/qualified-var-symbol v)]
                   (when-let [schema-desc (var-schema-desc v)]
                     [qualified-sym (schema-desc->typed-entry schema-desc)]))))
         (into {}))))
