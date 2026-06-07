(ns skeptic.analysis.call-kinds.static-output
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.shared-call :as shared-call]
            [skeptic.analysis.call-kinds.symbols :as symbols]
            [skeptic.analysis.types :as at]))

(s/defn static-call-output-type :- (s/maybe at/SemanticType)
  [ctx :- s/Any
   node :- s/Any
   args :- [s/Any]
   default-output-type :- at/SemanticType]
  (cond
    (symbols/static-get? node) (shared-call/shared-call-output-type ctx :get args default-output-type)
    (symbols/static-merge? node) (shared-call/shared-call-output-type ctx :merge args default-output-type)
    (and (symbols/static-assoc? node) (>= (count args) 3))
    (shared-call/shared-call-output-type ctx :assoc args default-output-type)
    (and (symbols/static-dissoc? node) (>= (count args) 2))
    (shared-call/shared-call-output-type ctx :dissoc args default-output-type)
    (and (symbols/static-update? node) (>= (count args) 3))
    (shared-call/shared-call-output-type ctx :update args default-output-type)
    (symbols/static-contains? node) (shared-call/shared-call-output-type ctx :contains args default-output-type)
    (and (= 'seq (:method node)) (= 1 (count args)))
    (shared-call/shared-call-output-type ctx :seq args default-output-type)
    :else nil))
