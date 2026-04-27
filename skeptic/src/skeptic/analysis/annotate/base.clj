(ns skeptic.analysis.annotate.base
  (:require [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(defn- annotate-child
  [ctx value]
  (cond
    (vector? value) (mapv #((:recurse ctx) ctx %) value)
    (map? value) ((:recurse ctx) ctx value)
    :else value))

(defn annotate-children
  [ctx node]
  (reduce (fn [acc key]
            (assoc acc key (annotate-child ctx (get acc key))))
          node
          (:children node)))

(defn annotate-const
  [ctx node]
  (assoc node :type (av/type-of-value (prov/with-ctx ctx) (:val node))))

(defn annotate-binding
  [ctx node]
  (if-let [init (:init node)]
    (let [annotated-init ((:recurse ctx) ctx init)]
      (merge node {:init annotated-init} (ac/node-info annotated-init)))
    node))

(defn- local-origin-for-entry
  [ctx sym entry t]
  (if (at/semantic-type-value? entry)
    (ao/root-origin sym (aapi/normalize-type ctx t))
    (:origin entry)))

(defn annotate-local
  [ctx node]
  (let [{:keys [locals assumptions]} ctx
        sym (:form node)
        entry (get locals sym)]
    (if (nil? entry)
      (assoc node :type (aapi/dyn ctx))
      (let [t (ao/effective-type ctx sym entry assumptions)
            origin (local-origin-for-entry ctx sym entry t)]
        (cond-> (merge node
                       (when (map? entry) (select-keys entry [:binding-init :fn-binding-node]))
                       {:type t})
          origin (assoc :origin origin))))))

(defn annotate-var-like
  [ctx node]
  (let [{:keys [dict ns accessor-summaries assumptions]} ctx
        entry (ac/lookup-type dict ns node)
        type (or entry (aapi/dyn ctx))
        qualified (ac/qualify-symbol ns (:form node))
        summary (get accessor-summaries qualified)
        origin (ao/root-origin (:form node) (aapi/normalize-type ctx type))
        refined (or (some-> origin (ao/origin-type assumptions)) type)]
    (cond-> (assoc node :type refined :origin origin)
      summary (assoc :accessor-summary summary))))
