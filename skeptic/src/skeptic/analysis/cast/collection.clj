(ns skeptic.analysis.cast.collection
  (:require [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value-check :as avc]))

(defn- aligned-children
  [run-child kind source-items target-items opts]
  (mapv (fn [idx source-item target-item]
          (run-child (ascs/indexed-request kind idx source-item target-item opts)))
        (range)
        source-items
        target-items))

(defn- expand-items
  [type slot-count]
  (if (:homogeneous? type)
    (vec (repeat slot-count (or (first (:items type)) at/Dyn)))
    (:items type)))

(defn- slot-count
  [source-type target-type]
  (let [source-count (count (:items source-type))
        target-count (count (:items target-type))]
    (cond
      (= source-count target-count) source-count
      (and (:homogeneous? target-type) (= 1 target-count)) source-count
      (and (:homogeneous? source-type) (= 1 source-count)) target-count
      :else nil)))

(defn- expanded-collection-result
  [run-child source-type target-type opts kind rule item-failure arity-failure]
  (if-let [count' (slot-count source-type target-type)]
    (let [children (aligned-children run-child
                                     kind
                                     (expand-items source-type count')
                                     (expand-items target-type count')
                                     opts)]
      (ascs/aggregate-children source-type target-type rule (:polarity opts) item-failure children))
    (ascs/cast-fail source-type target-type rule (:polarity opts) arity-failure)))

(defn- fixed-collection-result
  [run-child source-type target-type opts kind rule item-failure arity-failure]
  (let [source-items (:items source-type)
        target-items (:items target-type)]
    (if (= (count source-items) (count target-items))
      (let [children (aligned-children run-child kind source-items target-items opts)]
        (ascs/aggregate-children source-type target-type rule (:polarity opts) item-failure children))
      (ascs/cast-fail source-type target-type rule (:polarity opts) arity-failure))))

(defn- set-member-failure
  [source-member target-members polarity]
  (ascs/with-cast-path
    (ascs/cast-fail source-member
                    (or (first target-members) at/Dyn)
                    :set-element
                    polarity
                    :element-mismatch)
    {:kind :set-member
     :member source-member}))

(defn- set-member-result
  [run-child source-member target-members opts]
  (let [results (mapv #(run-child {:source-type source-member
                                   :target-type %
                                   :opts opts})
                      target-members)]
    (or (some #(when (:ok? %) %) results)
        (set-member-failure source-member target-members (:polarity opts)))))

(defn check-vector-cast
  [run-child source-type target-type opts]
  (expanded-collection-result run-child
                              source-type
                              target-type
                              opts
                              :vector-index
                              :vector
                              :vector-element-failed
                              :vector-arity-mismatch))

(defn check-seq-cast
  [run-child source-type target-type opts]
  (fixed-collection-result run-child
                           source-type
                           target-type
                           opts
                           :seq-index
                           :seq
                           :seq-element-failed
                           :seq-arity-mismatch))

(defn check-seq-to-vector-cast
  [run-child source-type target-type opts]
  (expanded-collection-result run-child
                              source-type
                              target-type
                              opts
                              :vector-index
                              :seq-to-vector
                              :seq-to-vector-element-failed
                              :seq-to-vector-arity-mismatch))

(defn check-vector-to-seq-cast
  [run-child source-type target-type opts]
  (expanded-collection-result run-child
                              source-type
                              target-type
                              opts
                              :seq-index
                              :vector-to-seq
                              :vector-to-seq-element-failed
                              :vector-to-seq-arity-mismatch))

(defn check-set-cast
  [run-child source-type target-type opts]
  (if (= (count (:members source-type)) (count (:members target-type)))
    (let [children (mapv #(set-member-result run-child % (:members target-type) opts)
                         (:members source-type))]
      (ascs/aggregate-children source-type target-type :set (:polarity opts) :set-element-failed children))
    (ascs/cast-fail source-type target-type :set (:polarity opts) :set-cardinality-mismatch)))

(defn check-leaf-cast
  [source-type target-type polarity]
  (cond
    (at/value-type? source-type)
    (if (avc/value-satisfies-type? (:value source-type) target-type)
      (ascs/cast-ok source-type target-type :value-exact)
      (ascs/cast-fail source-type target-type :value-exact polarity :exact-value-mismatch))

    (at/value-type? target-type)
    (if (avc/value-satisfies-type? (:value target-type) source-type)
      (ascs/cast-ok source-type target-type :target-value)
      (ascs/cast-fail source-type target-type :target-value polarity :target-value-mismatch))

    (or (at/dyn-type? source-type) (at/placeholder-type? source-type))
    (ascs/cast-ok source-type target-type :residual-dynamic)

    (or (at/ground-type? source-type)
        (at/refinement-type? source-type)
        (at/adapter-leaf-type? source-type))
    (if (avc/leaf-overlap? source-type target-type)
      (ascs/cast-ok source-type target-type :leaf-overlap)
      (ascs/cast-fail source-type target-type :leaf-overlap polarity :leaf-mismatch))

    (at/fun-type? source-type)
    (if (and (at/adapter-leaf-type? target-type)
             ((:accepts? target-type) identity))
      (ascs/cast-ok source-type target-type :fun-fn-pred)
      (ascs/cast-fail source-type target-type :fun-fn-pred polarity :mismatch))

    :else
    (ascs/cast-fail source-type target-type :mismatch polarity :mismatch)))
