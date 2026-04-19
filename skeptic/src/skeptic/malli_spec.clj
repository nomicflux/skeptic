(ns skeptic.malli-spec
  (:require [schema.core :as s]))

(s/defschema ArgList
  {:arglist [s/Any]
   (s/optional-key :count) s/Int
   (s/optional-key :malli-spec) s/Any})

(s/defschema ArgListKey
  (s/cond-pre s/Int (s/eq :varargs)))

(s/defschema MalliSpecDesc
  {:name s/Str
   :malli-spec s/Any
   (s/optional-key :output) (s/maybe s/Any)
   (s/optional-key :arglists) {ArgListKey ArgList}
   (s/optional-key :malli/source) s/Any})
