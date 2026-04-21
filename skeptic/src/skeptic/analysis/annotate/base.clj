(ns skeptic.analysis.annotate.base
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]))

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
  [_ctx node]
  (assoc node :type (av/type-of-value (:val node))))

(defn annotate-binding
  [ctx node]
  (if-let [init (:init node)]
    (let [annotated-init ((:recurse ctx) ctx init)]
      (merge node {:init annotated-init} (ac/node-info annotated-init)))
    node))

(defn- local-origin-for-entry
  [sym entry t]
  (if (at/semantic-type-value? entry)
    (ao/root-origin sym (ato/normalize-type-for-declared-type t))
    (:origin entry)))

(defn annotate-local
  [{:keys [locals assumptions]} node]
  (let [sym (:form node)
        entry (get locals sym)]
    (if (nil? entry)
      (assoc node :type at/Dyn)
      (let [t (ao/effective-type sym entry assumptions)
            origin (local-origin-for-entry sym entry t)]
        (cond-> (merge node
                       (when (map? entry) (select-keys entry [:binding-init :fn-binding-node]))
                       {:type t})
          origin (assoc :origin origin))))))

(defn annotate-var-like
  [{:keys [dict ns accessor-summaries]} node]
  (let [entry (ac/lookup-type dict ns node)
        qualified (ac/qualify-symbol ns (:form node))
        summary (get accessor-summaries qualified)]
    (cond-> (assoc node :type (or entry at/Dyn))
      summary (assoc :accessor-summary summary))))
