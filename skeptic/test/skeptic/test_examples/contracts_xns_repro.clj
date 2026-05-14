(ns skeptic.test-examples.contracts-xns-repro
  (:require [schema.core :as s]
            [skeptic.test-examples.contracts-xns-consumer :as consumer]
            [skeptic.test-examples.contracts-xns-schema :as schema]))

(s/defn b-params? :- s/Bool
  [{:keys [k]} :- schema/Either]
  (vector? k))

(s/defn repro-success
  [{:keys [k] :as params} :- schema/Either]
  (cond
    (b-params? params) nil
    (some? k) (consumer/consume-single params)))

(s/defn repro-failure-not-narrowed-enough
  [{:keys [k] :as params} :- schema/Either]
  (cond
    (some? k) (consumer/consume-single params)
    :else nil))

(s/defn repro-failure-wrong-branch
  [{:keys [k] :as params} :- schema/Either]
  (cond
    (b-params? params) (consumer/consume-single params)
    :else nil))
