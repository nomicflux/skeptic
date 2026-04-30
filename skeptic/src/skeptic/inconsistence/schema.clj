(ns skeptic.inconsistence.schema
  (:require [schema.core :as s]
            [skeptic.analysis.cast.schema :as csch]
            [skeptic.analysis.types.schema :as ats]))

(s/defschema CastSummary
  {:rule s/Keyword
   :actual-type ats/SemanticType
   :expected-type ats/SemanticType
   (s/optional-key :ok?) s/Bool
   (s/optional-key :blame-side) s/Keyword
   (s/optional-key :blame-polarity) s/Keyword
   (s/optional-key :reason) (s/maybe s/Keyword)
   (s/optional-key :path) [s/Any]
   (s/optional-key :actual-key) s/Any
   (s/optional-key :expected-key) s/Any
   (s/optional-key :source-key-domain) s/Any})

(s/defschema OutputReport
  {:report-kind (s/eq :output)
   :blame s/Any
   :cast-summary CastSummary
   :cast-diagnostics [csch/LeafDiagnostic]
   (s/optional-key :focuses) [s/Any]
   (s/optional-key :source-expression) s/Any
   (s/optional-key :expanded-expression) s/Any
   (s/optional-key :location) s/Any
   (s/optional-key :enclosing-form) s/Any
   (s/optional-key :path) [s/Any]
   (s/optional-key :context) s/Any
   (s/optional-key :blame-side) s/Keyword
   (s/optional-key :blame-polarity) s/Keyword
   (s/optional-key :rule) s/Keyword
   (s/optional-key :expected-type) ats/SemanticType
   (s/optional-key :actual-type) ats/SemanticType
   (s/optional-key :focus-sources) [s/Any]
   (s/optional-key :errors) [s/Str]})

(s/defschema InputReport
  {:report-kind (s/eq :input)
   :blame s/Any
   :focuses [s/Any]
   :cast-diagnostics [csch/LeafDiagnostic]
   (s/optional-key :cast-summary) CastSummary
   (s/optional-key :source-expression) s/Any
   (s/optional-key :expanded-expression) s/Any
   (s/optional-key :location) s/Any
   (s/optional-key :enclosing-form) s/Any
   (s/optional-key :path) [s/Any]
   (s/optional-key :context) s/Any
   (s/optional-key :focus-sources) [s/Any]
   (s/optional-key :blame-side) s/Keyword
   (s/optional-key :blame-polarity) s/Keyword
   (s/optional-key :rule) s/Keyword
   (s/optional-key :expected-type) ats/SemanticType
   (s/optional-key :actual-type) ats/SemanticType
   (s/optional-key :errors) [s/Str]})

(s/defschema ExceptionReport
  {:report-kind (s/eq :exception)
   :phase s/Keyword
   :blame s/Any
   :exception-class s/Symbol
   :exception-message s/Str
   (s/optional-key :enclosing-form) s/Any
   (s/optional-key :namespace) s/Symbol
   (s/optional-key :location) s/Any
   (s/optional-key :exception-data) s/Any
   (s/optional-key :declaration-slot) s/Keyword
   (s/optional-key :rejected-schema) s/Any
   (s/optional-key :source-expression) s/Any})

(s/defschema Report
  (s/conditional
   #(= :output    (:report-kind %)) OutputReport
   #(= :input     (:report-kind %)) InputReport
   #(= :exception (:report-kind %)) ExceptionReport))

(s/defschema ReportCtx
  {:expr s/Any
   :arg s/Any
   (s/optional-key :expected-keys) [s/Any]})

(s/defschema ReportOpts
  {(s/optional-key :explain-full) s/Bool})
