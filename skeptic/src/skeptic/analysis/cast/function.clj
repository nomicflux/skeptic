(ns skeptic.analysis.cast.function
  (:require [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.cast.support :as ascs]))

(defn- domain-request
  [idx target-input source-input opts]
  {:source-type target-input
   :target-type source-input
   :opts (update opts :polarity abr/flip-polarity)
   :path-segment {:kind :function-domain
                  :index idx}})

(defn- range-request
  [source-output target-output opts]
  {:source-type source-output
   :target-type target-output
   :opts opts
   :path-segment {:kind :function-range}})

(defn- method-children
  [run-child source-method target-method opts]
  (let [domains (mapv (fn [idx target-input source-input]
                        (run-child (domain-request idx target-input source-input opts)))
                      (range)
                      (:inputs target-method)
                      (:inputs source-method))
        range-child (run-child (range-request (:output source-method) (:output target-method) opts))]
    (conj domains range-child)))

(defn- missing-method
  [source-type target-type target-method polarity]
  (ascs/cast-fail source-type
                  target-type
                  :function-arity
                  polarity
                  :arity-mismatch
                  []
                  {:target-method target-method}))

(defn- method-result
  [source-method target-method polarity children]
  (if (ascs/all-ok? children)
    (ascs/cast-ok source-method target-method :function-method children)
    (ascs/cast-fail source-method target-method :function-method polarity :function-component-failed children)))

(defn- check-function-method
  [run-child source-type target-method polarity opts]
  (if-let [source-method (ascs/matching-source-method source-type target-method)]
    (method-result source-method
                   target-method
                   polarity
                   (method-children run-child source-method target-method opts))
    (missing-method source-type nil target-method polarity)))

(defn check-function-cast
  [run-child source-type target-type polarity opts]
  (let [children (mapv #(check-function-method run-child source-type % polarity opts)
                       (:methods target-type))]
    (ascs/aggregate-children source-type target-type :function polarity :function-cast-failed children)))
