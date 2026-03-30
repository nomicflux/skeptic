(ns skeptic.schematize
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [skeptic.analysis.schema :as as]
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

(defn into-coll
  [f x]
  (cond
    (nil? x) []
    (coll? x) (mapcat #(into-coll f %) x)
    :else [(f x)]))

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

(s/defn collect-schemas :- dschema/SchemaDesc
  [{:keys [schema ns name arglists] :as this}]
  (try
    (as/canonicalize-entry
     (if (or (class? schema) (set? schema) (vector? schema))
       {:name (str (s/explain schema))
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
                                                    :schema (get inputs (:count arg)))

                                             (not (nil? input))
                                             (assoc :schema input)))))
                                {}
                                (keys args))]
         {:name (str ns "/" name)
          :schema schema
          :output (or output-schema schema)
          :arglists args-with-schemas})))
    (catch Exception e
      (println "Exception collecting schemas:" (pr-str this))
      (throw e))))

(s/defn fully-qualify-str :- s/Symbol
  [f :- s/Str]
  (-> f
      symbol
      resolve
      symbol))

(defn qualified-var-symbol
  [v]
  (let [{:keys [ns name]} (meta v)]
    (when (and ns name)
      (symbol (str (ns-name ns) "/" name)))))

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
        qualified-sym (qualified-var-symbol v)
        arglists (when (and (:arglists m)
                            (not (:macro m)))
                   (dynamic-arglists (:arglists m)))]
    (as/canonicalize-entry
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
                 (when-let [qualified-sym (qualified-var-symbol v)]
                   [qualified-sym (var-schema-desc v)])))
         (into {}))))
