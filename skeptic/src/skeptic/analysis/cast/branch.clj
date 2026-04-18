(ns skeptic.analysis.cast.branch
  (:require [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn- run-indexed-children
  [run-child kind pairs opts]
  (mapv (fn [idx [source-type target-type]]
          (run-child (ascs/indexed-request kind idx source-type target-type opts)))
        (range)
        pairs))

(defn- one-child-result
  [source-type target-type rule reason child opts]
  (if (:ok? child)
    (ascs/cast-ok source-type target-type rule [child])
    (ascs/cast-fail source-type target-type rule (:polarity opts) reason [child])))

(defn- source-union-result
  [run-child source-type target-type opts]
  (let [pairs (mapv #(vector % target-type) (:members source-type))
        children (run-indexed-children run-child :source-union-branch pairs opts)]
    (ascs/aggregate-children source-type
                             target-type
                             :source-union
                             (:polarity opts)
                             :source-branch-failed
                             children)))

(defn- target-union-result
  [run-child source-type target-type opts]
  (let [pairs (mapv #(vector source-type %) (:members target-type))
        children (run-indexed-children run-child :target-union-branch pairs opts)]
    (if-let [success (some #(when (:ok? %) %) children)]
      (ascs/cast-ok source-type target-type :target-union children {:chosen-rule (:rule success)})
      (ascs/cast-fail source-type target-type :target-union (:polarity opts) :no-union-branch children))))

(defn check-union-cast
  [run-child source-type target-type opts]
  (if (at/union-type? source-type)
    (source-union-result run-child source-type target-type opts)
    (target-union-result run-child source-type target-type opts)))

(defn- source-conditional-result
  [run-child source-type target-type opts]
  (let [pairs (mapv #(vector (second %) target-type) (:branches source-type))
        children (run-indexed-children run-child :source-union-branch pairs opts)]
    (ascs/aggregate-children source-type
                             target-type
                             :source-union
                             (:polarity opts)
                             :source-branch-failed
                             children)))

(defn- target-conditional-result
  [run-child source-type target-type opts]
  (let [pairs (mapv #(vector source-type (second %)) (:branches target-type))
        children (run-indexed-children run-child :target-union-branch pairs opts)]
    (if-let [success (some #(when (:ok? %) %) children)]
      (ascs/cast-ok source-type target-type :target-union children {:chosen-rule (:rule success)})
      (ascs/cast-fail source-type target-type :target-union (:polarity opts) :no-union-branch children))))

(defn check-conditional-cast
  [run-child source-type target-type opts]
  (if (at/conditional-type? source-type)
    (source-conditional-result run-child source-type target-type opts)
    (target-conditional-result run-child source-type target-type opts)))

(defn- target-intersection-result
  [run-child source-type target-type opts]
  (let [pairs (mapv #(vector source-type %) (:members target-type))
        children (run-indexed-children run-child :target-intersection-branch pairs opts)]
    (ascs/aggregate-children source-type
                             target-type
                             :target-intersection
                             (:polarity opts)
                             :target-component-failed
                             children)))

(defn- source-intersection-result
  [run-child source-type target-type opts]
  (let [pairs (mapv #(vector % target-type) (:members source-type))
        children (run-indexed-children run-child :source-intersection-branch pairs opts)]
    (ascs/aggregate-children source-type
                             target-type
                             :source-intersection
                             (:polarity opts)
                             :source-component-failed
                             children)))

(defn check-intersection-cast
  [run-child source-type target-type opts]
  (if (at/intersection-type? target-type)
    (target-intersection-result run-child source-type target-type opts)
    (source-intersection-result run-child source-type target-type opts)))

(defn- maybe-child
  [run-child source-type target-type opts]
  (run-child {:source-type source-type
              :target-type target-type
              :opts opts
              :path-segment {:kind :maybe-value}}))

(defn- nullable-target?
  [t]
  (or (at/maybe-type? t) (ato/nil-value-type? t)))

(defn- nullable-target-payload
  "Type carried when a value is present: Maybe's inner, or the whole singleton for (eq nil)."
  [t]
  (if (at/maybe-type? t) (:inner t) t))

(defn check-maybe-cast
  [run-child source-type target-type opts]
  (cond
    (and (at/maybe-type? source-type) (nullable-target? target-type))
    (if (at/maybe-type? target-type)
      (one-child-result source-type target-type :maybe-both :maybe-inner-failed
                        (maybe-child run-child (:inner source-type) (:inner target-type) opts)
                        opts)
      (one-child-result source-type target-type :maybe-to-eq-nil :maybe-inner-failed
                        (maybe-child run-child (:inner source-type) target-type opts)
                        opts))

    (and (nullable-target? target-type)
         (at/value-type? source-type)
         (nil? (:value source-type)))
    (ascs/cast-ok source-type target-type :nil-satisfies-maybe)

    (nullable-target? target-type)
    (one-child-result source-type target-type :maybe-target :maybe-target-inner-failed
                      (maybe-child run-child source-type (nullable-target-payload target-type) opts)
                      opts)

    :else
    (ascs/cast-fail source-type target-type :maybe-source (:polarity opts) :nullable-source)))

(defn- unwrap-wrapper
  [type]
  (cond
    (at/optional-key-type? type) (:inner type)
    (at/var-type? type) (:inner type)
    :else type))

(defn check-wrapper-cast
  [run-child source-type target-type opts]
  (if (or (at/optional-key-type? source-type)
          (at/var-type? source-type))
    (run-child {:source-type (unwrap-wrapper source-type)
                :target-type target-type
                :opts opts})
    (run-child {:source-type source-type
                :target-type (unwrap-wrapper target-type)
                :opts opts})))
