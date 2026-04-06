(ns skeptic.analysis.bridge.algebra
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb])
  (:import [schema.core One]))

(defn resolve-placeholders
  [schema resolve-placeholder]
  (cond
    (sb/placeholder-schema? schema)
    (or (resolve-placeholder (sb/placeholder-ref schema))
        schema)

    (sb/bottom-schema? schema)
    sb/Bottom

    (sb/fn-schema? schema)
    (let [{:keys [input-schemas output-schema]} (into {} schema)]
      (s/make-fn-schema (resolve-placeholders output-schema resolve-placeholder)
                        (mapv (fn [inputs]
                                (mapv (fn [one]
                                        (let [m (try (into {} one)
                                                     (catch Exception _e nil))]
                                          (if (map? m)
                                            (s/one (resolve-placeholders (:schema m) resolve-placeholder)
                                                   (:name m))
                                            one)))
                                      inputs))
                              input-schemas)))

    (instance? One schema)
    (abc/canonicalize-one (assoc (into {} schema)
                                :schema (resolve-placeholders (:schema schema)
                                                              resolve-placeholder)))

    (sb/maybe? schema)
    (s/maybe (resolve-placeholders (:schema schema) resolve-placeholder))

    (sb/join? schema)
    (abc/schema-join (set (map #(resolve-placeholders % resolve-placeholder)
                               (:schemas schema))))

    (sb/valued-schema? schema)
    (sb/valued-schema (resolve-placeholders (:schema schema) resolve-placeholder)
                      (:value schema))

    (sb/variable? schema)
    (sb/variable (resolve-placeholders (:schema schema) resolve-placeholder))

    (record? schema)
    schema

    (map? schema)
    (into {}
          (map (fn [[k v]]
                 [(resolve-placeholders k resolve-placeholder)
                  (resolve-placeholders v resolve-placeholder)]))
          schema)

    (vector? schema)
    (mapv #(resolve-placeholders % resolve-placeholder) schema)

    (set? schema)
    (into #{} (map #(resolve-placeholders % resolve-placeholder)) schema)

    (seq? schema)
    (doall (map #(resolve-placeholders % resolve-placeholder) schema))

    :else schema))
