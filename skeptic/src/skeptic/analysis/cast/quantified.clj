(ns skeptic.analysis.cast.quantified
  (:require [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.type-algebra :as ata]
            [skeptic.analysis.types :as at]))

(defn- quantified-failure
  [source-type target-type rule polarity reason child details]
  (ascs/cast-fail source-type target-type rule polarity reason [child] details))

(defn- quantified-success
  [source-type target-type rule binder child details opts]
  (let [exit-result (ascs/exit-nu-scope child binder opts)]
    (if (:ok? exit-result)
      (ascs/cast-ok source-type target-type rule [child] details)
      (merge (assoc exit-result
                    :source-type source-type
                    :target-type target-type
                    :children [child])
             details))))

(defn- generalize-cast
  [run-child source-type target-type polarity opts]
  (let [binder (:binder target-type)
        details (ascs/details-with-state opts {:binder binder})]
    (if (contains? (ata/type-free-vars source-type) binder)
      (ascs/cast-fail source-type target-type :generalize polarity :forall-capture [] details)
      (let [child (run-child {:source-type source-type
                              :target-type (:body target-type)
                              :opts opts})]
        (if (:ok? child)
          (quantified-success source-type target-type :generalize binder child details opts)
          (quantified-failure source-type target-type :generalize polarity :generalize-failed child details))))))

(defn- instantiate-cast
  [run-child source-type target-type polarity opts]
  (let [binder (:binder source-type)
        instantiated (ata/type-substitute (:body source-type) binder at/Dyn)
        details (ascs/details-with-state opts
                  {:binder binder
                   :instantiated-type instantiated})
        child (run-child {:source-type instantiated
                          :target-type target-type
                          :opts opts})]
    (if (:ok? child)
      (quantified-success source-type target-type :instantiate binder child details opts)
      (quantified-failure source-type target-type :instantiate polarity :instantiate-failed child details))))

(defn- sealed-match?
  [source-type target-type]
  (= (ascs/sealed-ground-name source-type)
     (ata/type-var-name target-type)))

(defn- type-var-target-result
  [source-type target-type polarity opts]
  (let [details (ascs/details-with-state opts)]
    (if (at/sealed-dyn-type? source-type)
      (if (sealed-match? source-type target-type)
        (ascs/cast-ok source-type target-type :sealed-collapse [] details)
        (ascs/cast-fail source-type target-type :sealed-collapse polarity :sealed-ground-mismatch [] details))
      (ascs/cast-fail source-type target-type :type-var-target polarity :abstract-target-mismatch [] details))))

(defn check-abstract-cast
  [source-type target-type polarity opts]
  (let [details (ascs/details-with-state opts)]
    (cond
      (and (at/type-var-type? source-type) (at/dyn-type? target-type))
      (let [sealed-type (at/->SealedDynT source-type)]
        (ascs/cast-ok source-type target-type :seal [] (assoc details :sealed-type sealed-type)))

      (at/type-var-type? target-type)
      (type-var-target-result source-type target-type polarity opts)

      (at/type-var-type? source-type)
      (ascs/cast-fail source-type target-type :type-var-source polarity :abstract-source-mismatch [] details)

      :else
      (ascs/cast-fail source-type target-type :sealed-conflict polarity :sealed-mismatch [] details))))

(defn check-quantified-cast
  [run-child source-type target-type polarity opts]
  (if (at/forall-type? target-type)
    (generalize-cast run-child source-type target-type polarity opts)
    (instantiate-cast run-child source-type target-type polarity opts)))
