(ns skeptic.analysis.cast.function
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.cast.schema :as csch]
            [skeptic.analysis.cast.support :as ascs]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(s/defn map-as-function :- at/SemanticType
  "A map called as a function looks up its argument:
   (=> union-of-value-types union-of-key-types). Really a dependent function;
   not modeled yet. Empty unions follow union-type's convention (Dyn)."
  [map-type :- at/SemanticType]
  (let [p (prov/of map-type)
        entries (:entries map-type)
        input (ato/union-type p (keys entries))
        output (ato/union-type p (vals entries))]
    (at/->FunT p [(at/->FnMethodT p [input] output 1 false '[k])])))

(s/defn set-as-function :- at/SemanticType
  "A set called as a function looks up its argument, returning it on a hit
   and nil on a miss: (=> (maybe union-of-member-types) union-of-member-types)."
  [set-type :- at/SemanticType]
  (let [p (prov/of set-type)
        input (ato/union-type p (:members set-type))]
    (at/->FunT p [(at/->FnMethodT p [input] (at/->MaybeT p input) 1 false '[k])])))

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
  [source-type target-type target-method opts]
  (ascs/cast-fail source-type
                  target-type
                  :function-arity
                  (:polarity opts)
                  :arity-mismatch
                  []
                  {:target-method target-method}))

(defn- method-result
  [source-method target-method opts children]
  (if (ascs/all-ok? children)
    (ascs/cast-ok source-method target-method :function-method children)
    (ascs/cast-fail source-method
                    target-method
                    :function-method
                    (:polarity opts)
                    :function-component-failed
                    children)))

(defn- check-function-method
  [run-child source-type target-method opts]
  (if-let [source-method (ascs/matching-source-method source-type target-method)]
    (method-result source-method
                   target-method
                   opts
                   (method-children run-child source-method target-method opts))
    (missing-method source-type target-method target-method opts)))

(s/defn check-function-cast :- csch/CastResult
  [run-child :- (s/pred fn?) source-type :- at/SemanticType target-type :- at/SemanticType opts :- s/Any]
  (let [children (mapv #(check-function-method run-child source-type % opts)
                       (:methods target-type))]
    (ascs/aggregate-children source-type
                             target-type
                             :function
                             (:polarity opts)
                             :function-cast-failed
                             children)))
