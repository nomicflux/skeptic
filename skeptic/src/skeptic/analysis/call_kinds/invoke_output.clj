(ns skeptic.analysis.call-kinds.invoke-output
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.shared-call :as shared-call]
            [skeptic.analysis.call-kinds.symbols :as symbols]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defn- unary-summary-output
  [fn-node args]
  (let [summary (:accessor-summary fn-node)]
    (when (and (= :unary-identity (:kind summary))
               (= 1 (count args)))
      (:type (first args)))))

(s/defn invoke-output-type :- at/SemanticType
  [ctx :- s/Any
   fn-node :- s/Any
   args :- [s/Any]
   output-type :- at/SemanticType]
  (if-let [summary-output (unary-summary-output fn-node args)]
    summary-output
    (let [arity (count args)]
      (cond
        (symbols/get? fn-node) (shared-call/shared-call-output-type ctx :get args output-type)
        (symbols/merge? fn-node) (shared-call/shared-call-output-type ctx :merge args output-type)
        (and (symbols/assoc? fn-node) (>= arity 3))
        (shared-call/shared-call-output-type ctx :assoc args output-type)
        (and (symbols/dissoc? fn-node) (>= arity 2))
        (shared-call/shared-call-output-type ctx :dissoc args output-type)
        (and (symbols/update? fn-node) (>= arity 3))
        (shared-call/shared-call-output-type ctx :update args output-type)
        (symbols/contains-call? fn-node) (shared-call/shared-call-output-type ctx :contains args output-type)
        (and (symbols/first? fn-node) (= 1 arity))
        (or (coll/coll-first-type (:type (first args))) output-type)
        (and (symbols/second? fn-node) (= 1 arity))
        (or (coll/coll-second-type (:type (first args))) output-type)
        (and (symbols/last? fn-node) (= 1 arity))
        (or (coll/coll-last-type (:type (first args))) output-type)
        (and (symbols/nth? fn-node) (>= arity 2))
        (or (coll/invoke-nth-output-type args) output-type)
        (and (symbols/rest? fn-node) (= 1 arity))
        (or (coll/coll-rest-output-type (:type (first args))) output-type)
        (and (symbols/butlast? fn-node) (= 1 arity))
        (or (coll/coll-butlast-output-type (:type (first args))) output-type)
        (and (symbols/drop-last? fn-node) (or (= 1 arity) (= 2 arity)))
        (let [drop-count (if (= 1 arity)
                           1
                           (coll/const-long-value (first args)))]
          (or (when drop-count
                (coll/coll-drop-last-output-type (:type (last args)) drop-count))
              output-type))
        (and (symbols/take? fn-node) (= 2 arity))
        (or (when-let [take-count (coll/const-long-value (first args))]
              (coll/coll-take-prefix-type (:type (second args)) take-count))
            (coll/coll-same-element-seq-type (:type (second args)))
            output-type)
        (and (symbols/drop? fn-node) (= 2 arity))
        (or (when-let [drop-count (coll/const-long-value (first args))]
              (coll/coll-drop-prefix-type (:type (second args)) drop-count))
            (coll/coll-same-element-seq-type (:type (second args)))
            output-type)
        (and (symbols/take-while? fn-node) (= 2 arity))
        (or (coll/coll-same-element-seq-type (:type (second args))) output-type)
        (and (symbols/drop-while? fn-node) (= 2 arity))
        (or (coll/coll-same-element-seq-type (:type (second args))) output-type)
        (symbols/concat? fn-node) (or (coll/concat-output-type (ato/derive-prov output-type) args) output-type)
        (symbols/into? fn-node) (or (coll/into-output-type args) output-type)
        (and (symbols/chunk-first? fn-node) (= 1 arity))
        (or (when-let [elem (coll/seqish-element-type (:type (first args)))]
              (let [prov (ato/derive-prov elem)]
                (at/->SeqT (prov/with-refs prov [(prov/of elem)])
                           (at/pattern-from-prefix-tail [] (ato/normalize-type prov elem))
                           :sequential)))
            output-type)
        (and (symbols/seq-call? fn-node) (= 1 arity))
        (shared-call/shared-call-output-type ctx :seq args output-type)
        :else output-type))))
