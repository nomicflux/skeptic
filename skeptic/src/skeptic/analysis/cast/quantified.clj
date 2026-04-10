(ns skeptic.analysis.cast.quantified
  (:require [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.type-algebra :as ata]
            [skeptic.analysis.types :as at]))

(defn- quantified-failure
  [source-type target-type rule reason child opts details]
  (ascs/cast-fail source-type
                  target-type
                  rule
                  (:polarity opts)
                  reason
                  [child]
                  details))

(defn- quantified-exit-failure
  [source-type target-type child details exit-result]
  (assoc exit-result
         :source-type source-type
         :target-type target-type
         :children [child]
         :binder (:binder details)
         :instantiated-type (:instantiated-type details)))

(defn- quantified-result
  [source-type target-type rule binder child opts details]
  (let [exit-result (ascs/exit-nu-scope child binder opts)]
    (if (:ok? exit-result)
      (ascs/cast-ok source-type target-type rule [child] details)
      (quantified-exit-failure source-type target-type child details exit-result))))

(defn- generalize-cast
  [run-child source-type target-type opts]
  (let [binder (:binder target-type)
        details {:binder binder}]
    (if (contains? (ata/type-free-vars source-type) binder)
      (ascs/cast-fail source-type target-type :generalize (:polarity opts) :forall-capture [] details)
      (let [child (run-child {:source-type source-type
                              :target-type (:body target-type)
                              :opts opts})]
        (if (:ok? child)
          (quantified-result source-type target-type :generalize binder child opts details)
          (quantified-failure source-type target-type :generalize :generalize-failed child opts details))))))

(defn- instantiate-cast
  [run-child source-type target-type opts]
  (let [binder (:binder source-type)
        instantiated (ata/type-substitute (:body source-type) binder at/Dyn)
        details {:binder binder
                 :instantiated-type instantiated}
        child (run-child {:source-type instantiated
                          :target-type target-type
                          :opts opts})]
    (if (:ok? child)
      (quantified-result source-type target-type :instantiate binder child opts details)
      (quantified-failure source-type target-type :instantiate :instantiate-failed child opts details))))

(defn- sealed-match?
  [source-type target-type]
  (= (ascs/sealed-ground-name source-type)
     (ata/type-var-name target-type)))

(defn- type-var-target-result
  [source-type target-type opts]
  (let [polarity (:polarity opts)]
    (if (at/sealed-dyn-type? source-type)
      (if (sealed-match? source-type target-type)
        (ascs/cast-ok source-type target-type :sealed-collapse)
        (ascs/cast-fail source-type target-type :sealed-collapse polarity :sealed-ground-mismatch))
      (ascs/cast-fail source-type target-type :type-var-target polarity :abstract-target-mismatch))))

(defn check-abstract-cast
  [source-type target-type opts]
  (let [polarity (:polarity opts)]
    (cond
      (and (at/type-var-type? source-type) (at/dyn-type? target-type))
      (let [sealed-type (at/->SealedDynT source-type)]
        (ascs/cast-ok source-type target-type :seal [] {:sealed-type sealed-type}))

      (at/type-var-type? target-type)
      (type-var-target-result source-type target-type opts)

      (at/type-var-type? source-type)
      (ascs/cast-fail source-type target-type :type-var-source polarity :abstract-source-mismatch)

      :else
      (ascs/cast-fail source-type target-type :sealed-conflict polarity :sealed-mismatch))))

(defn check-quantified-cast
  [run-child source-type target-type opts]
  (if (at/forall-type? target-type)
    (generalize-cast run-child source-type target-type opts)
    (instantiate-cast run-child source-type target-type opts)))
