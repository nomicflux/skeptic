(ns skeptic.cljs.analyzer-driver.schema
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.schema :as aas]))

(s/defschema RawCljsAst
  "Pre-`strip-cljs-type` cljs analyzer node: carries `:op` plus possibly
   a classification `:type` slot that conflicts with skeptic's SemanticType
   `:type` and possibly a missing `:form` on `:binding` nodes. Open map."
  {:op       s/Keyword
   s/Keyword s/Any})

(s/defschema SourceFormEntry
  {:source-form              s/Any
   :ast                      (s/maybe aas/AnnotatedNode)
   (s/optional-key :exception)    Throwable
   (s/optional-key :malli-schema) s/Any})

(s/defschema SourceFileAnalysis
  {:ns-ast  aas/AnnotatedNode
   :entries [SourceFormEntry]
   :asts    [aas/AnnotatedNode]})
