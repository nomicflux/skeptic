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

(s/defschema LeafDiagnostic
  {:rule                          s/Keyword
   :reason                        (s/maybe s/Keyword)
   :path                          [s/Any]
   :actual-type                   ats/SemanticType
   :expected-type                 ats/SemanticType
   :blame-side                    s/Keyword
   :blame-polarity                s/Keyword
   (s/optional-key :actual-key)        s/Any
   (s/optional-key :expected-key)      s/Any
   (s/optional-key :source-key-domain) s/Any})

(s/defschema RootSummary
  {:ok?            s/Bool
   :rule           s/Keyword
   :blame-side     s/Keyword
   :blame-polarity s/Keyword
   :actual-type    ats/SemanticType
   :expected-type  ats/SemanticType})
