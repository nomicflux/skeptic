(ns skeptic.analysis.cast
  (:require [skeptic.analysis.cast.branch :as branch]
            [skeptic.analysis.cast.collection :as coll]
            [skeptic.analysis.cast.function :as fun]
            [skeptic.analysis.cast.map :as cmap]
            [skeptic.analysis.cast.quantified :as quant]
            [skeptic.analysis.cast.result :as result]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn- run-child
  [run-cast {:keys [source-type target-type opts path-segment]}]
  (cond-> (run-cast source-type target-type opts)
    path-segment (ascs/with-cast-path path-segment)))

(defn- dispatch-cast
  [run-cast source-type target-type opts]
  (let [child-run #(run-child run-cast %)]
    (cond
      (at/bottom-type? source-type)
      (ascs/cast-ok source-type target-type :bottom-source)

      (at/type-equal? source-type target-type)
      (ascs/cast-ok source-type target-type :exact)

      (or (at/forall-type? target-type) (at/forall-type? source-type))
      (quant/check-quantified-cast child-run source-type target-type opts)

      (or (at/type-var-type? target-type)
          (at/type-var-type? source-type)
          (at/sealed-dyn-type? source-type))
      (quant/check-abstract-cast source-type target-type opts)

      (at/dyn-type? target-type)
      (ascs/cast-ok source-type target-type :target-dyn)

      (or (at/union-type? target-type) (at/union-type? source-type))
      (branch/check-union-cast child-run source-type target-type opts)

      (or (at/intersection-type? target-type) (at/intersection-type? source-type))
      (branch/check-intersection-cast child-run source-type target-type opts)

      (or (at/conditional-type? target-type) (at/conditional-type? source-type))
      (branch/check-conditional-cast child-run source-type target-type opts)

      (or (at/maybe-type? source-type) (at/maybe-type? target-type))
      (branch/check-maybe-cast child-run source-type target-type opts)

      (or (at/optional-key-type? source-type)
          (at/optional-key-type? target-type)
          (at/var-type? source-type)
          (at/var-type? target-type))
      (branch/check-wrapper-cast child-run source-type target-type opts)

      (and (at/fun-type? source-type) (at/fun-type? target-type))
      (fun/check-function-cast child-run source-type target-type opts)

      (and (at/map-type? source-type) (at/map-type? target-type))
      (cmap/check-map-cast child-run source-type target-type opts)

      (and (at/vector-type? source-type) (at/vector-type? target-type))
      (coll/check-vector-cast child-run source-type target-type opts)

      (and (at/seq-type? source-type) (at/seq-type? target-type))
      (coll/check-seq-cast child-run source-type target-type opts)

      (and (at/seq-type? source-type) (at/vector-type? target-type))
      (coll/check-seq-to-vector-cast child-run source-type target-type opts)

      (and (at/vector-type? source-type) (at/seq-type? target-type))
      (coll/check-vector-to-seq-cast child-run source-type target-type opts)

      (and (at/set-type? source-type) (at/set-type? target-type))
      (coll/check-set-cast child-run source-type target-type opts)

      :else
      (coll/check-leaf-cast source-type target-type (:polarity opts)))))

(defn- run-cast
  [source-type target-type opts]
  (dispatch-cast run-cast source-type target-type opts))

(defn check-cast
  ([source-type target-type]
   (check-cast source-type target-type {}))
  ([source-type target-type {:keys [polarity] :or {polarity :positive} :as opts}]
   (let [source-type (ato/normalize source-type)
         target-type (ato/normalize target-type)
         opts (assoc opts :polarity polarity)]
     (run-cast source-type target-type opts))))

(defn compatible?
  [source-type target-type]
  (result/ok? (check-cast source-type target-type)))
