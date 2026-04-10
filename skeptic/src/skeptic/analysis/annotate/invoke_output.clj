(ns skeptic.analysis.annotate.invoke-output
  (:require [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.shared-call :as shared-call]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn invoke-output-type
  [fn-node args output-type]
  (cond
    (ac/get-call? fn-node) (shared-call/shared-call-output-type :get args output-type)
    (ac/merge-call? fn-node) (shared-call/shared-call-output-type :merge args output-type)
    (and (ac/assoc-call? fn-node) (>= (count args) 3))
    (shared-call/shared-call-output-type :assoc args output-type)
    (and (ac/dissoc-call? fn-node) (>= (count args) 2))
    (shared-call/shared-call-output-type :dissoc args output-type)
    (and (ac/update-call? fn-node) (>= (count args) 3))
    (shared-call/shared-call-output-type :update args output-type)
    (ac/contains-call? fn-node) (shared-call/shared-call-output-type :contains args output-type)
    (and (ac/first-call? fn-node) (= 1 (count args)))
    (or (coll/coll-first-type (:type (first args))) output-type)
    (and (ac/second-call? fn-node) (= 1 (count args)))
    (or (coll/coll-second-type (:type (first args))) output-type)
    (and (ac/last-call? fn-node) (= 1 (count args)))
    (or (coll/coll-last-type (:type (first args))) output-type)
    (and (ac/nth-call? fn-node) (>= (count args) 2))
    (or (coll/invoke-nth-output-type args) output-type)
    (and (ac/rest-call? fn-node) (= 1 (count args)))
    (or (coll/coll-rest-output-type (:type (first args))) output-type)
    (and (ac/butlast-call? fn-node) (= 1 (count args)))
    (or (coll/coll-butlast-output-type (:type (first args))) output-type)
    (and (ac/drop-last-call? fn-node) (or (= 1 (count args)) (= 2 (count args))))
    (let [drop-count (if (= 1 (count args))
                       1
                       (coll/const-long-value (first args)))]
      (or (when drop-count
            (coll/coll-drop-last-output-type (:type (last args)) drop-count))
          output-type))
    (and (ac/take-call? fn-node) (= 2 (count args)))
    (or (when-let [take-count (coll/const-long-value (first args))]
          (coll/coll-take-prefix-type (:type (second args)) take-count))
        (coll/coll-same-element-seq-type (:type (second args)))
        output-type)
    (and (ac/drop-call? fn-node) (= 2 (count args)))
    (or (when-let [drop-count (coll/const-long-value (first args))]
          (coll/coll-drop-prefix-type (:type (second args)) drop-count))
        (coll/coll-same-element-seq-type (:type (second args)))
        output-type)
    (and (ac/take-while-call? fn-node) (= 2 (count args)))
    (or (coll/coll-same-element-seq-type (:type (second args))) output-type)
    (and (ac/drop-while-call? fn-node) (= 2 (count args)))
    (or (coll/coll-same-element-seq-type (:type (second args))) output-type)
    (ac/concat-call? fn-node) (or (coll/concat-output-type args) output-type)
    (ac/into-call? fn-node) (or (coll/into-output-type args) output-type)
    (and (ac/chunk-first-call? fn-node) (= 1 (count args)))
    (or (when-let [elem (coll/seqish-element-type (:type (first args)))]
          (at/->SeqT [(ato/normalize-type elem)] true))
        output-type)
    (and (ac/seq-call? fn-node) (= 1 (count args)))
    (shared-call/shared-call-output-type :seq args output-type)
    :else output-type))
