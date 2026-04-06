(ns skeptic.analysis.schema.cast
  (:require [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as ac]))

(defn- import-boundary-type
  [schema]
  (ab/import-schema-type schema))

(defn check-cast
  ([source-type target-type]
   (check-cast source-type target-type {}))
  ([source-type target-type {:keys [polarity] :or {polarity :positive} :as opts}]
   (let [source-type (import-boundary-type source-type)
         target-type (import-boundary-type target-type)
         opts (assoc opts :polarity polarity)]
     (ac/check-cast source-type target-type opts))))
