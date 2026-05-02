(ns skeptic.analysis.annotate.schema
  (:require [schema.core :as s]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.types.schema :as ats]))

(s/defschema AnnotatedNode
  {:op                                  s/Keyword
   :form                                s/Any
   (s/optional-key :children)           [s/Keyword]
   (s/optional-key :type)               ats/SemanticType
   (s/optional-key :output-type)        ats/SemanticType
   (s/optional-key :fn-type)            ats/SemanticType
   (s/optional-key :origin)             (s/maybe aos/Origin)
   (s/optional-key :expected-argtypes)  [ats/SemanticType]
   (s/optional-key :actual-argtypes)    [ats/SemanticType]
   s/Keyword                            s/Any})

(s/defschema CaseNode
  (assoc AnnotatedNode
         :tests [s/Any]
         :thens [s/Any]))

(s/defschema IfNode
  (assoc AnnotatedNode :test AnnotatedNode :then AnnotatedNode :else AnnotatedNode))

(s/defschema LetNode
  (assoc AnnotatedNode :bindings [AnnotatedNode] :body AnnotatedNode))

(s/defschema LoopNode
  (assoc AnnotatedNode :bindings [AnnotatedNode] :body AnnotatedNode))

(s/defschema RecurNode
  (assoc AnnotatedNode :exprs [AnnotatedNode]))

(s/defschema InvokeNode
  (assoc AnnotatedNode :fn AnnotatedNode :args [AnnotatedNode]))

(s/defschema StaticCallNode
  (assoc AnnotatedNode :class s/Any :method s/Symbol :args [AnnotatedNode]))

(s/defschema InstanceCallNode
  (assoc AnnotatedNode :instance AnnotatedNode :method s/Symbol :args [AnnotatedNode]))

(s/defschema KeywordInvokeNode
  (assoc AnnotatedNode :target AnnotatedNode :keyword AnnotatedNode))

(s/defschema FnMethodNode
  (assoc AnnotatedNode :body AnnotatedNode))

(s/defschema FnNode
  (assoc AnnotatedNode :methods [AnnotatedNode]))

(s/defschema BindingNode
  (assoc AnnotatedNode :name s/Symbol (s/optional-key :init) AnnotatedNode))

(s/defschema LocalNode
  (assoc AnnotatedNode :name s/Symbol (s/optional-key :binding-init) AnnotatedNode))

(s/defschema WithMetaNode
  (assoc AnnotatedNode :expr AnnotatedNode))

(s/defschema DefNode
  (assoc AnnotatedNode :name s/Symbol (s/optional-key :init) AnnotatedNode))

(s/defschema CallNode
  (s/conditional
   #(= :invoke (:op %))         InvokeNode
   #(= :static-call (:op %))    StaticCallNode
   #(= :instance-call (:op %))  InstanceCallNode
   #(= :keyword-invoke (:op %)) KeywordInvokeNode
   #(= :recur (:op %))          RecurNode))

(s/defschema BindingHolder
  "Either an annotated `:local` AST node or a locals-map entry. Both shapes
   carry an optional `:binding-init` slot pointing at the init AnnotatedNode.
   AST nodes additionally have :op/:form; locals-map entries are a flat
   projection of node-info plus `:origin`/`:binding-init`/`:fn-binding-node`."
  {(s/optional-key :binding-init) AnnotatedNode
   s/Keyword                      s/Any})

(s/defschema RawAnalyzerAst
  "Shape produced by clojure.tools.analyzer.jvm/analyze before skeptic
   normalization. The analyzer puts its own classification keyword in
   :type on :const nodes (e.g. :vector, :keyword); skeptic normalizes
   that away at the analyze-form boundary so :type is unambiguously
   a SemanticType in AnnotatedNode."
  {:op       s/Keyword
   :form     s/Any
   s/Keyword s/Any})
