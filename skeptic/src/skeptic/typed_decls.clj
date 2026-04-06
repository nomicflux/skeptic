(ns skeptic.typed-decls
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.schema.collect :as collect]))

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

(defn desc->typed-entry
  [{:keys [name schema arglists] :as desc}]
  (when desc
    (if (or (sb/fn-schema? schema)
            (seq arglists))
      (callable-desc->typed-entry desc)
      {:name name
       :type (ab/schema->type schema)})))

(defn typed-ns-entries
  [opts ns]
  (->> (collect/ns-schemas opts ns)
       (keep (fn [[qualified-sym schema-desc]]
               (when-let [typed-entry (desc->typed-entry schema-desc)]
                 [qualified-sym typed-entry])))
       (into {})))
