(ns skeptic.analysis.normalize
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

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
               (catch Exception _e {}))
        type (normalize-declared-type (or (:type m) (:schema m) s/Any))]
    {:type type
     :schema (compat-schema type)
     :optional? false
     :name (or (:name m) (symbol (str "arg" idx)))}))

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
