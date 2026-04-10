(ns skeptic.analysis.annotate.invoke-output
  (:require [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.annotate.shared-call :as aasc]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]))

(defn invoke-output-type
  "Single cond chain; order matches legacy annotate-invoke."
  [fn-node args output-type]
  (cond
    (ac/get-call? fn-node)
    (aasc/shared-call-output-type :get args output-type)

    (ac/merge-call? fn-node)
    (aasc/shared-call-output-type :merge args output-type)

    (and (ac/assoc-call? fn-node)
         (>= (count args) 3))
    (aasc/shared-call-output-type :assoc args output-type)

    (and (ac/dissoc-call? fn-node)
         (>= (count args) 2))
    (aasc/shared-call-output-type :dissoc args output-type)

    (and (ac/update-call? fn-node)
         (>= (count args) 3))
    (aasc/shared-call-output-type :update args output-type)

    (ac/contains-call? fn-node)
    (aasc/shared-call-output-type :contains args output-type)

    (and (ac/first-call? fn-node)
         (= 1 (count args)))
    (or (aac/coll-first-type (:type (first args)))
        output-type)

    (and (ac/second-call? fn-node)
         (= 1 (count args)))
    (or (aac/coll-second-type (:type (first args)))
        output-type)

    (and (ac/last-call? fn-node)
         (= 1 (count args)))
    (or (aac/coll-last-type (:type (first args)))
        output-type)

    (and (ac/nth-call? fn-node)
         (>= (count args) 2))
    (or (aac/invoke-nth-output-type args)
        output-type)

    (and (ac/rest-call? fn-node)
         (= 1 (count args)))
    (or (aac/coll-rest-output-type (:type (first args)))
        output-type)

    (and (ac/butlast-call? fn-node)
         (= 1 (count args)))
    (or (aac/coll-butlast-output-type (:type (first args)))
        output-type)

    (and (ac/drop-last-call? fn-node)
         (or (= 1 (count args)) (= 2 (count args))))
    (or (if (= 1 (count args))
          (aac/coll-drop-last-output-type (:type (first args)) 1)
          (when-let [n (aac/const-long-value (first args))]
            (aac/coll-drop-last-output-type (:type (second args)) n)))
        output-type)

    (and (ac/take-call? fn-node)
         (= 2 (count args)))
    (or (when-let [n (aac/const-long-value (first args))]
          (aac/coll-take-prefix-type (:type (second args)) n))
        (aac/coll-same-element-seq-type (:type (second args)))
        output-type)

    (and (ac/drop-call? fn-node)
         (= 2 (count args)))
    (or (when-let [n (aac/const-long-value (first args))]
          (aac/coll-drop-prefix-type (:type (second args)) n))
        (aac/coll-same-element-seq-type (:type (second args)))
        output-type)

    (and (ac/take-while-call? fn-node)
         (= 2 (count args)))
    (or (aac/coll-same-element-seq-type (:type (second args)))
        output-type)

    (and (ac/drop-while-call? fn-node)
         (= 2 (count args)))
    (or (aac/coll-same-element-seq-type (:type (second args)))
        output-type)

    (ac/concat-call? fn-node)
    (or (aac/concat-output-type args)
        output-type)

    (ac/into-call? fn-node)
    (or (aac/into-output-type args)
        output-type)

    (and (ac/chunk-first-call? fn-node) (= 1 (count args)))
    (or (when-let [e (aac/seqish-element-type (:type (first args)))]
          (at/->SeqT [(ato/normalize-type e)] true))
        output-type)

    (and (ac/seq-call? fn-node)
         (= 1 (count args)))
    (aasc/shared-call-output-type :seq args output-type)

    :else
    output-type))
