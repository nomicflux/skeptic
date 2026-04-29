(ns skeptic.analysis.annotate.invoke-output
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.shared-call :as shared-call]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defn- unary-summary-output
  [fn-node args]
  (let [summary (:accessor-summary fn-node)]
    (when (and (= :unary-identity (:kind summary))
               (= 1 (count args)))
      (:type (first args)))))

(s/defn invoke-output-type :- s/Any
  [ctx fn-node args output-type call-sym]
  (if-let [summary-output (unary-summary-output fn-node args)]
    summary-output
    (let [arity (count args)]
      (cond
        (contains? ac/get-call-syms call-sym) (shared-call/shared-call-output-type ctx :get args output-type)
        (contains? ac/merge-call-syms call-sym) (shared-call/shared-call-output-type ctx :merge args output-type)
        (and (contains? ac/assoc-call-syms call-sym) (>= arity 3))
        (shared-call/shared-call-output-type ctx :assoc args output-type)
        (and (contains? ac/dissoc-call-syms call-sym) (>= arity 2))
        (shared-call/shared-call-output-type ctx :dissoc args output-type)
        (and (contains? ac/update-call-syms call-sym) (>= arity 3))
        (shared-call/shared-call-output-type ctx :update args output-type)
        (contains? ac/contains-call-syms call-sym) (shared-call/shared-call-output-type ctx :contains args output-type)
        (and (contains? ac/first-call-syms call-sym) (= 1 arity))
        (or (coll/coll-first-type (:type (first args))) output-type)
        (and (contains? ac/second-call-syms call-sym) (= 1 arity))
        (or (coll/coll-second-type (:type (first args))) output-type)
        (and (contains? ac/last-call-syms call-sym) (= 1 arity))
        (or (coll/coll-last-type (:type (first args))) output-type)
        (and (contains? ac/nth-call-syms call-sym) (>= arity 2))
        (or (coll/invoke-nth-output-type args) output-type)
        (and (contains? ac/rest-call-syms call-sym) (= 1 arity))
        (or (coll/coll-rest-output-type (:type (first args))) output-type)
        (and (contains? ac/butlast-call-syms call-sym) (= 1 arity))
        (or (coll/coll-butlast-output-type (:type (first args))) output-type)
        (and (contains? ac/drop-last-call-syms call-sym) (or (= 1 arity) (= 2 arity)))
        (let [drop-count (if (= 1 arity)
                           1
                           (coll/const-long-value (first args)))]
          (or (when drop-count
                (coll/coll-drop-last-output-type (:type (last args)) drop-count))
              output-type))
        (and (contains? ac/take-call-syms call-sym) (= 2 arity))
        (or (when-let [take-count (coll/const-long-value (first args))]
              (coll/coll-take-prefix-type (:type (second args)) take-count))
            (coll/coll-same-element-seq-type (:type (second args)))
            output-type)
        (and (contains? ac/drop-call-syms call-sym) (= 2 arity))
        (or (when-let [drop-count (coll/const-long-value (first args))]
              (coll/coll-drop-prefix-type (:type (second args)) drop-count))
            (coll/coll-same-element-seq-type (:type (second args)))
            output-type)
        (and (contains? ac/take-while-call-syms call-sym) (= 2 arity))
        (or (coll/coll-same-element-seq-type (:type (second args))) output-type)
        (and (contains? ac/drop-while-call-syms call-sym) (= 2 arity))
        (or (coll/coll-same-element-seq-type (:type (second args))) output-type)
        (contains? ac/concat-call-syms call-sym) (or (coll/concat-output-type (ato/derive-prov output-type) args) output-type)
        (contains? ac/into-call-syms call-sym) (or (coll/into-output-type args) output-type)
        (and (contains? ac/chunk-first-call-syms call-sym) (= 1 arity))
        (or (when-let [elem (coll/seqish-element-type (:type (first args)))]
              (let [prov (ato/derive-prov elem)]
                (at/->SeqT (prov/with-refs prov [(prov/of elem)]) [(ato/normalize-type prov elem)] true)))
            output-type)
        (and (contains? ac/seq-call-syms call-sym) (= 1 arity))
        (shared-call/shared-call-output-type ctx :seq args output-type)
        :else output-type))))
