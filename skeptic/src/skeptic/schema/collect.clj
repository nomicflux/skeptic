(ns skeptic.schema.collect
  (:require [clojure.string :as str]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
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

(defn normalize-vararg-input-schemas
  [schemas]
  (let [schemas (vec schemas)]
    (if (seq schemas)
      (conj (pop schemas) (schema-entry-schema (peek schemas)))
      schemas)))

(defn- invalid-schema-annotation
  [ns name slot value e]
  (ex-info (format "Invalid schema annotation for %s/%s in %s: %s"
                   ns
                   name
                   (pr-str slot)
                   (pr-str value))
           {:declaration-slot slot
            :rejected-schema (pr-str value)}
           e))

(defn- schema-slots
  [desc]
  (concat [[:schema (:schema desc)]
           [:output (:output desc)]]
          (for [[slot entry] (:arglists desc)]
            [[:arglist slot] (:schema entry)])))

(defn- assert-admitted-schema-slots!
  "Admission boundary only: explicit :schema, :output, and arglist :schema slots."
  [ns name desc]
  (doseq [[slot value] (schema-slots desc)]
    (when (some? value)
      (try
        (ab/admit-schema value)
        (catch IllegalArgumentException e
          (throw (invalid-schema-annotation ns name slot value e)))))))

(defn- annotated-arg-entry
  [inputs args next]
  (let [input (get inputs next)
        arg (get args next)]
    (cond-> {:arglist arg}
      (= next :varargs)
      (assoc :count (:count arg)
             :arglist (:args arg)
             :schema (some-> (get inputs (:count arg))
                             normalize-vararg-input-schemas))

      (not (nil? input))
      (assoc :schema input))))

(defn- annotated-args-map
  [input-schemas arglists]
  (let [inputs (count-map input-schemas)
        args (arg-map arglists)]
    (reduce (fn [acc next]
              (assoc acc next (annotated-arg-entry inputs args next)))
            {}
            (keys args))))

(defn- fn-schema-desc
  [schema ns name arglists]
  (let [{:keys [input-schemas output-schema]} (into {} schema)]
    {:name (str ns "/" name)
     :schema schema
     :output (or output-schema schema)
     :arglists (annotated-args-map input-schemas arglists)}))

(defn- class-schema-desc
  [schema ns name]
  {:name (str ns "/" name)
   :schema schema
   :output schema
   :arglists {}})

(defn- build-annotated-schema-desc!
  [{:keys [schema ns name arglists]}]
  (let [schema (abc/canonicalize-schema schema)
        desc (if (or (class? schema) (set? schema) (vector? schema))
               (class-schema-desc schema ns name)
               (fn-schema-desc schema ns name arglists))]
    (assert-admitted-schema-slots! ns name desc)
    desc))

(s/defn collect-schemas :- dschema/SchemaDesc
  [{:keys [schema ns name arglists]}]
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

(defn declaration-error-result
  ([ns-sym qualified-sym v e]
   (declaration-error-result :declaration ns-sym qualified-sym v e))
  ([phase ns-sym qualified-sym v e]
   (merge {:report-kind :exception
           :phase phase
           :blame qualified-sym
           :enclosing-form qualified-sym
           :namespace ns-sym
           :location (assoc (select-keys (meta v) [:file :line :column :end-line :end-column])
                            :source :schema)
           :exception-class (symbol (.getName (class e)))
           :exception-message (or (.getMessage e)
                                  (str e))
           :exception-data (pr-str (ex-data e))}
          (-> (select-keys (or (ex-data e) {})
                           [:declaration-slot :rejected-schema])
              (update :declaration-slot #(some-> % pr-str))
              (update :rejected-schema #(some-> % pr-str))))))
