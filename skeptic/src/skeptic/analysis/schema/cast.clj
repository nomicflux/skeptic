(ns skeptic.analysis.schema.cast
  (:require [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as ac]
            [skeptic.analysis.cast.result :as result]))

(defn- import-boundary-type
  [prov schema]
  (ab/import-schema-type prov schema))

(defn check-cast
  ([prov source-type target-type]
   (check-cast prov source-type target-type {}))
  ([prov source-type target-type {:keys [polarity] :or {polarity :positive} :as opts}]
   (let [source-type (import-boundary-type prov source-type)
         target-type (import-boundary-type prov target-type)
         opts (assoc opts :polarity polarity)]
     (ac/check-cast source-type target-type opts))))

(defn compatible?
  [prov source-type target-type]
  (result/ok? (check-cast prov source-type target-type)))
