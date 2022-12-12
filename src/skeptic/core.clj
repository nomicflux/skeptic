(ns skeptic.core
  (:require [skeptic.schematize :as schematize]
            [schema.core :as s]))

(s/defn ns-schemas
  [ns :- s/Str]
  (->> ns
       symbol
       ns-publics
       vals
       (map symbol)
       (map schematize/attach-schema-info-to-qualified-symbol)
       (reduce merge {})))
