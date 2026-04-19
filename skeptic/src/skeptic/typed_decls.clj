(ns skeptic.typed-decls
  (:require [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.malli-spec.collect :as mcollect]
            [skeptic.schema.collect :as collect]))

(declare typed-ns-results)

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

(defn- admitted-arg-map
  [one]
  (if (map? one)
    one
    (into {} one)))

(defn one->typed-arg-entry
  [idx one]
  (let [m (admitted-arg-map one)]
    {:name (or (:name m) (raw-arg-name idx nil))
     :optional? (boolean (:optional? m))
     :type (ab/schema->type (:schema m))}))

(defn arglist->typed-entry
  [entry]
  (let [schemas (vec (or (:schema entry) []))
        arglist (:arglist entry)
        types (mapv (fn [idx schema-item]
                      (let [typed-entry (one->typed-arg-entry idx schema-item)
                            m (admitted-arg-map schema-item)
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

(defn callable-desc->typed-entry
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

(defn- malli-desc->typed-entry
  [{:keys [name malli-spec]}]
  {:name name
   :type (amb/malli-spec->type malli-spec)})

(defn desc->typed-entry
  [{:keys [name schema malli-spec arglists] :as desc}]
  (when desc
    (let [entry (cond
                  (some? malli-spec)
                  (malli-desc->typed-entry desc)

                  (or (sb/fn-schema? schema)
                      (seq arglists))
                  (callable-desc->typed-entry desc)

                  :else
                  {:name name
                   :type (ab/schema->type schema)})]
      (cond-> entry
        (:skeptic/ignore-body? desc)
        (assoc :skeptic/ignore-body? true)))))

(defn- convert-descs
  [ns descs initial-errors]
  (reduce (fn [{:keys [entries errors]} [qualified-sym desc]]
            (try
              (if-let [typed-entry (desc->typed-entry desc)]
                {:entries (assoc entries qualified-sym typed-entry)
                 :errors errors}
                {:entries entries
                 :errors errors})
              (catch Exception e
                {:entries entries
                 :errors (conj errors
                               (collect/declaration-error-result ns
                                                                 qualified-sym
                                                                 (resolve qualified-sym)
                                                                 e))})))
          {:entries {} :errors (vec initial-errors)}
          descs))

(defn typed-ns-entries
  [opts ns]
  (:entries (typed-ns-results opts ns)))

(defn typed-ns-results
  [opts ns]
  (let [{schema-entries :entries schema-errors :errors} (collect/ns-schema-results opts ns)
        {malli-entries :entries malli-errors :errors} (mcollect/ns-malli-spec-results opts ns)
        descs (merge schema-entries malli-entries)
        initial-errors (concat schema-errors malli-errors)
        result (convert-descs ns descs initial-errors)]
    (update result :entries merge (:skeptic/type-overrides opts))))
