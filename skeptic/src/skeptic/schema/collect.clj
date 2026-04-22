(ns skeptic.schema.collect
  (:require [clojure.string :as str]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.checking.form :as cf]
            [skeptic.file :as file]
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
  [v]
  (boolean (-> v meta :skeptic/opaque)))

(defn normalize-vararg-input-schemas
  [schemas]
  (let [schemas (vec schemas)]
    (if (seq schemas)
      (conj (pop schemas) (schema-entry-schema (peek schemas)))
      schemas)))

(defn- annotated-raw
  [qualified-sym m v]
  {:kind :annotated
   :qualified-sym qualified-sym
   :raw-schema (:schema m)
   :ns (ns-name (:ns m))
   :name (:name m)
   :raw-arglists (:arglists m)
   :var v})

(defn extract-raw-declaration
  "Returns annotated raw declaration when var has :schema metadata, nil otherwise."
  [v]
  (when-let [qualified-sym (sb/qualified-var-symbol v)]
    (let [m (meta v)]
      (when (and (not (:macro m)) (:schema m) (not (opaque? v)))
        (cond-> (annotated-raw qualified-sym m v)
          (ignore-body? v)
          (assoc :skeptic/ignore-body? true))))))

(defn- invalid-schema-annotation
  [ns name slot value e]
  (ex-info (format "Invalid schema annotation for %s/%s in %s: %s"
                   ns
                   name
                   (pr-str slot)
                   (pr-str value))
           {:declaration-slot slot
            :rejected-schema value}
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

(defn admit-declaration-from-extract
  "Phase 2: schema admission for explicit annotation slots only."
  [raw]
  (let [desc (build-annotated-schema-desc! {:schema (:raw-schema raw)
                                            :ns (:ns raw)
                                            :name (:name raw)
                                            :arglists (:raw-arglists raw)})]
    (cond-> desc
      (:skeptic/ignore-body? raw)
      (assoc :skeptic/ignore-body? true))))

(defn var-schema-desc
  [v]
  (when-let [raw (extract-raw-declaration v)]
    (admit-declaration-from-extract raw)))

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
           :exception-data (ex-data e)}
          (select-keys (or (ex-data e) {})
                       [:declaration-slot :rejected-schema]))))

(defn ns-schema-results
  [_opts ns]
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
            (concat (vals (ns-interns ns))
                    (vals (ns-refers ns))))))

(defn ns-schemas
  [opts ns]
  (:entries (ns-schema-results opts ns)))

(defn- annotation-sym-for-form
  [form]
  (or (cf/extract-def-annotation-symbol form)
      (cf/extract-defn-annotation-symbol form)))

(defn- declared-name-sym
  [form]
  (when (seq? form) (second form)))

(defn- resolve-in-ns
  [ns-sym sym]
  (binding [*ns* (the-ns ns-sym)]
    (ns-resolve (the-ns ns-sym) sym)))

(defn- put-annotation-entry!
  [^java.util.IdentityHashMap acc ns-sym form]
  (when-let [ann-sym (annotation-sym-for-form form)]
    (when-let [decl-sym (declared-name-sym form)]
      (when-let [decl-var (resolve-in-ns ns-sym decl-sym)]
        (when-let [ann-var (resolve-in-ns ns-sym ann-sym)]
          (.put acc decl-var ann-var))))))

(defn build-annotation-refs!
  [^java.util.IdentityHashMap acc ns-sym source-file]
  (try
    (with-open [reader (file/pushback-reader source-file)]
      (->> (repeatedly #(file/try-read reader))
           (take-while some?)
           (remove file/is-ns-block?)
           (run! #(put-annotation-entry! acc ns-sym %))))
    (catch Exception _))
  acc)
