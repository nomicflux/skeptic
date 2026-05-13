(ns skeptic.analysis.cast.schema
  (:require [schema.core :as s]
            [skeptic.analysis.types :as at]))

(s/defschema CastResult
  {:ok?            s/Bool
   :blame-side     s/Keyword
   :blame-polarity s/Keyword
   :rule           s/Keyword
   :source-type    at/SemanticType
   :target-type    at/SemanticType
   :children       [(s/recursive #'CastResult)]
   :reason         (s/maybe s/Keyword)
   s/Keyword       s/Any})

(s/defschema LeafDiagnostic
  {:rule                          s/Keyword
   :reason                        (s/maybe s/Keyword)
   :path                          [s/Any]
   :actual-type                   at/SemanticType
   :expected-type                 at/SemanticType
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
   :actual-type    at/SemanticType
   :expected-type  at/SemanticType})
