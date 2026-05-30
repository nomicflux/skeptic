(ns skeptic.analysis.annotate.base
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.prune :as prune]
            [skeptic.analysis.annotate.runner :as runner]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(s/defn annotate-children :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (letfn [(walk [cur-node remaining]
            (if (empty? remaining)
              (runner/done cur-node)
              (let [k (first remaining)
                    v (get cur-node k)]
                (cond
                  (vector? v)
                  (runner/sequence-children ctx v
                    (fn [vs] (walk (assoc cur-node k vs) (rest remaining))))
                  (map? v)
                  (runner/call (:recurse-step ctx) ctx v
                    (fn [v'] (walk (assoc cur-node k v') (rest remaining))))
                  :else
                  (walk cur-node (rest remaining))))))]
    (walk node (:children node))))

(s/defn annotate-const :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)]
    (assoc node :type
           (if (:val-display-name node)
             (av/class->type prov java.lang.Class)
             (av/type-of-value prov (:val node))))))

(s/defn annotate-binding :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (if-let [init (:init node)]
    (runner/call (:recurse-step ctx) ctx init
                 (fn [annotated-init]
                   (let [pruned (prune/project-node annotated-init)]
                     (runner/done
                      (merge node {:init pruned} (ac/node-info pruned))))))
    (runner/done (assoc node :type (aapi/dyn ctx)))))

(defn- local-origin-for-entry
  [ctx sym entry t]
  (cond
    (and (map? entry) (not (record? entry)))
    (:origin entry)

    (at/semantic-type-value? entry)
    (ao/root-origin sym (aapi/normalize-type ctx t))

    :else
    (:origin entry)))

(s/defn annotate-local :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
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

(s/defn annotate-var-like :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [{:keys [dict ns accessor-summaries assumptions]} ctx
        entry (ac/lookup-type dict ns node)
        type (or entry (aapi/dyn ctx))
        summary (ac/lookup-summary accessor-summaries ns node)
        origin (ao/root-origin (:form node) (aapi/normalize-type ctx type))
        refined (or (some-> origin (ao/origin-type assumptions)) type)]
    (cond-> (assoc node :type refined :origin origin)
      summary (assoc :accessor-summary summary))))
