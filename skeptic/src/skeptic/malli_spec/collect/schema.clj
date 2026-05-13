(ns skeptic.malli-spec.collect.schema
  (:require [schema.core :as s]
            [skeptic.schema.collect.schema :as scs]))

(s/defschema MalliErrorResult
  {:report-kind        (s/eq :exception)
   :phase              s/Keyword
   :blame              s/Symbol
   :enclosing-form     s/Symbol
   :namespace          s/Symbol
   :location           scs/ErrorLocation
   :exception-class    s/Symbol
   :exception-message  s/Str
   :exception-data     s/Any})

(s/defschema MalliEntry
  {:name       s/Str
   :malli-spec s/Any})

(s/defschema MalliAdmissionResult
  {:entries {s/Symbol MalliEntry}
   :errors  [MalliErrorResult]})
