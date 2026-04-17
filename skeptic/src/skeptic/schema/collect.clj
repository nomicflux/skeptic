(ns skeptic.schema.collect
  (:require [clojure.string :as str]
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
  "Unwrap a One or similar arg-schema entry, preserving map structure."
  [schema]
  (let [m (try (into {} schema)
               (catch Exception _e nil))]
    (if (and (map? m) (contains? m :schema))
      m
      {:schema schema})))

(defn ignore-body?
  [v]
  (boolean (-> v meta :skeptic/ignore-body)))

(defn opaque?
  [_v]
  false)

(defn normalize-vararg-input-schemas
  [schemas]
  (let [schemas (vec schemas)]
    (if (seq schemas)
      (conj (pop schemas) (schema-entry-schema (peek schemas)))
      schemas)))

(defn extract-raw-declaration
  "Phase 1: var metadata only. No canonicalization, schema?, or schema->type."
  [v]
  (when-let [qualified-sym (sb/qualified-var-symbol v)]
    (let [m (meta v)]
      (when-not (:macro m)
        (cond-> (if (:schema m)
                  {:kind :annotated
                   :qualified-sym qualified-sym
                   :raw-schema (:schema m)
                   :ns (ns-name (:ns m))
                   :name (:name m)
                   :raw-arglists (:arglists m)
                   :var v}
                  {:kind :dynamic
                   :qualified-sym qualified-sym
                   :var v})
          (ignore-body? v)
          (assoc :skeptic/ignore-body? true))))))

(defn- assert-admitted-schema-slots!
  "Admission boundary only: explicit :schema, :output, and arglist :schema slots."
  [ns name desc]
  (doseq [value (concat [(:schema desc) (:output desc)]
                        (keep :schema (vals (:arglists desc))))]
    (when (some? value)
      (when-not (abc/schema? value)
        (throw (IllegalArgumentException.
                (format "Invalid schema annotation for %s/%s: %s"
                        ns
                        name
                        (pr-str value))))))))

(defn- build-annotated-schema-desc!
  [{:keys [schema ns name arglists]}]
  (let [schema (abc/canonicalize-schema schema)
        desc (->> (if (or (class? schema) (set? schema) (vector? schema))
                    {:name (or (some-> schema abc/schema-display-form pr-str) (str ns "/" name))
                     :schema schema
                     :output schema
                     :arglists {}}
                    (let [{:keys [input-schemas output-schema]} (into {} schema)
                          inputs (count-map input-schemas)
                          args (arg-map arglists)
                          annotated-args (reduce
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
                       :arglists annotated-args}))
                  abc/canonicalize-entry)]
    (assert-admitted-schema-slots! ns name desc)
    desc))

(s/defn collect-schemas :- dschema/SchemaDesc
  [{:keys [schema ns name arglists] :as this}]
  (build-annotated-schema-desc! {:schema schema
                                 :ns ns
                                 :name name
                                 :arglists arglists}))

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
                                                [{:schema s/Any}])))

                    (not= k :varargs)
                    (assoc :schema (mapv dynamic-arg-entry arglist)))]))
       (into {})))

(defn admit-dynamic-desc
  [v]
  (let [m (meta v)
        qualified-sym (sb/qualified-var-symbol v)
        arglists (when (and (:arglists m)
                            (not (:macro m)))
                   (dynamic-arglists (:arglists m)))
        desc (->> {:name (str qualified-sym)
                   :schema s/Any
                   :output s/Any
                   :arglists (or arglists {})}
                  abc/canonicalize-entry)]
    (assert-admitted-schema-slots! (some-> qualified-sym namespace symbol)
                                   qualified-sym
                                   desc)
    desc))

(defn admit-declaration-from-extract
  "Phase 2: schema admission for explicit annotation slots only."
  [raw]
  (let [desc (case (:kind raw)
               :annotated (build-annotated-schema-desc! {:schema (:raw-schema raw)
                                                         :ns (:ns raw)
                                                         :name (:name raw)
                                                         :arglists (:raw-arglists raw)})
               :dynamic (admit-dynamic-desc (:var raw)))]
    (cond-> desc
      (:skeptic/ignore-body? raw)
      (assoc :skeptic/ignore-body? true))))

(defn var-schema-desc
  [v]
  (when-let [raw (extract-raw-declaration v)]
    (admit-declaration-from-extract raw)))

(defn declaration-error-result
  [ns-sym qualified-sym v e]
  {:report-kind :exception
   :phase :declaration
   :blame qualified-sym
   :enclosing-form qualified-sym
   :namespace ns-sym
   :location (select-keys (meta v) [:file :line :column :end-line :end-column])
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(defn ns-schema-results
  [opts ns]
  (binding [*ns* (the-ns ns)]
    (reduce (fn [{:keys [entries errors] :as acc} v]
              (if-let [raw (extract-raw-declaration v)]
                (let [qualified-sym (:qualified-sym raw)]
                  (try
                    {:entries (assoc entries qualified-sym (admit-declaration-from-extract raw))
                     :errors errors}
                    (catch Exception e
                      {:entries entries
                       :errors (conj errors (declaration-error-result ns qualified-sym v e))})))
                acc))
            {:entries {}
             :errors []}
            (-> ns symbol ns-interns vals))))

(defn ns-schemas
  [opts ns]
  (:entries (ns-schema-results opts ns)))
