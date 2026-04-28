(ns skeptic.analysis.types.schema
  (:require [schema.core :as s]
            [skeptic.analysis.types.proto :as proto]))

(s/defschema SemanticType
  (s/protocol proto/SemanticType))
