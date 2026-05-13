(ns skeptic.schema.collect.schema
  (:require [schema.core :as s]
            [skeptic.provenance.schema :as provs]
            [skeptic.schema :as dschema]))

(s/defschema ErrorLocation
  {:source                            provs/Source
   (s/optional-key :file)             s/Any
   (s/optional-key :line)             s/Int
   (s/optional-key :column)           s/Int
   (s/optional-key :end-line)         s/Int
   (s/optional-key :end-column)       s/Int})

(s/defschema SchemaErrorResult
  {:report-kind                       (s/eq :exception)
   :phase                             s/Keyword
   :blame                             s/Symbol
   :enclosing-form                    s/Symbol
   :namespace                         s/Symbol
   :location                          ErrorLocation
   :exception-class                   s/Symbol
   :exception-message                 s/Str
   :exception-data                    s/Any
   (s/optional-key :declaration-slot) s/Any
   (s/optional-key :rejected-schema)  s/Any})

(s/defschema SchemaAdmitOutcome
  (s/conditional
   :ok  {:ok  dschema/SchemaDesc}
   :err {:err SchemaErrorResult}))

(s/defschema SchemaAdmissionResult
  {:entries {s/Symbol dschema/SchemaDesc}
   :errors  [SchemaErrorResult]})
