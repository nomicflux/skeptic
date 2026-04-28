(ns skeptic.analysis.annotate.schema
  (:require [schema.core :as s]
            [skeptic.analysis.origin.schema :as aos]))

(s/defschema AnnotatedNode
  (s/maybe
   {(s/optional-key :op)                  s/Keyword
    (s/optional-key :form)                s/Any
    (s/optional-key :children)            [s/Keyword]
    (s/optional-key :type)                s/Any
    (s/optional-key :output-type)         s/Any
    (s/optional-key :origin)              (s/maybe aos/Origin)
    s/Keyword                             s/Any}))
