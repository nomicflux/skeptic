(ns skeptic.schema
  (:require [schema.core :as s]))

(s/defschema ArgList
  {:arglist [s/Any]
   (s/optional-key :schema) s/Any})

(s/defschema SchemaDesc
  {:name s/Str
   :schema (s/maybe s/Any)
   :output (s/maybe s/Any)
   :arglists {s/Int ArgList}})
