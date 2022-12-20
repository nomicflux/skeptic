(ns skeptic.schema
  (:require [schema.core :as s]))

(s/defschema ArgList
  {:arglist [s/Any]
   (s/optional-key :count) s/Int
   (s/optional-key :schema) s/Any})

(s/defschema ArgListKey
  (s/cond-pre s/Int (s/eq :varargs)))

(s/defschema SchemaDesc
  {:name s/Str
   :schema (s/maybe s/Any)
   :output (s/maybe s/Any)
   :arglists {ArgListKey ArgList}})
