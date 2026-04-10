(ns skeptic.analysis.annotate.invoke-output
  (:require [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.annotate.map-path :as aamp]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]))

(defn- invoke-branch-get
  [args]
  (let [[target key-node default-node] args
        key-type (ac/get-key-query key-node)]
    (if default-node
      (amo/map-get-type (:type target)
                        key-type
                        (:type default-node))
      (amo/map-get-type (:type target)
                        key-type))))

(defn- invoke-branch-merge
  [args]
  (amoa/merge-types (map :type args)))

(defn- invoke-branch-assoc
  [args]
  (let [[m & kvs] args]
    (aamp/reduce-assoc-pairs (:type m) (partition 2 kvs))))

(defn- invoke-branch-dissoc
  [args]
  (let [[m & ks] args]
    (aamp/reduce-dissoc-keys (:type m) ks)))

(defn- invoke-branch-update
  [args output-type]
  (let [[m kn uf] args
        lk (when (ac/literal-map-key? kn)
             (ac/literal-node-value kn))]
    (if (keyword? lk)
      (amoa/update-type (:type m) lk (:type uf))
      output-type)))

(defn- invoke-branch-seq-one-arg
  [args]
  (let [t (:type (first args))]
    (or (cond
          (at/seq-type? t) t
          (at/vector-type? t) (aac/vector-to-homogeneous-seq-type t)
          :else nil)
        at/Dyn)))

(defn invoke-output-type
  "Single cond chain; order matches legacy annotate-invoke."
  [fn-node args output-type]
  (cond
    (ac/get-call? fn-node)
    (invoke-branch-get args)

    (ac/merge-call? fn-node)
    (invoke-branch-merge args)

    (and (ac/assoc-call? fn-node)
         (>= (count args) 3))
    (invoke-branch-assoc args)

    (and (ac/dissoc-call? fn-node)
         (>= (count args) 2))
    (invoke-branch-dissoc args)

    (and (ac/update-call? fn-node)
         (>= (count args) 3))
    (invoke-branch-update args output-type)

    (ac/contains-call? fn-node)
    aan/bool-type

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
    (invoke-branch-seq-one-arg args)

    :else
    output-type))
