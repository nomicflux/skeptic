(ns skeptic.analysis.schema
  (:require [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.cast :as ac]
            [skeptic.analysis.type-ops :as ato]))

(defn- as-type
  [value]
  (if (abc/schema? value)
    (ab/import-schema-type value)
    (ato/normalize-type value)))

(defn check-cast
  ([source-type target-type]
   (check-cast source-type target-type {}))
  ([source-type target-type {:keys [polarity] :or {polarity :positive} :as opts}]
   (let [source-type (as-type source-type)
         target-type (as-type target-type)
         opts (assoc opts :polarity polarity)]
     (ac/check-cast source-type target-type opts))))

(defn schema-compatible?
  [expected actual]
  (:ok? (check-cast (as-type actual) (as-type expected))))
