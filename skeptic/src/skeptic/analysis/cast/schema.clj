(ns skeptic.analysis.cast.schema
  (:require [schema.core :as s]
            [skeptic.analysis.types.schema :as ats]))

(s/defschema CastResult
  {:ok?            s/Bool
   :blame-side     s/Keyword
   :blame-polarity s/Keyword
   :rule           s/Keyword
   :source-type    ats/SemanticType
   :target-type    ats/SemanticType
   :children       [(s/recursive #'CastResult)]
   :reason         (s/maybe s/Keyword)
   s/Keyword       s/Any})
