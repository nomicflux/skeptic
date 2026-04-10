(ns skeptic.analysis.annotate.base
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
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

(defn annotate-local
  [{:keys [locals assumptions]} node]
  (merge node
         (if-let [entry (get locals (:form node))]
           (ao/effective-entry (:form node) entry assumptions)
           {:type at/Dyn})))

(defn annotate-var-like
  [{:keys [dict ns]} node]
  (merge node
         (or (ac/lookup-entry dict ns node)
             {:type at/Dyn})))
