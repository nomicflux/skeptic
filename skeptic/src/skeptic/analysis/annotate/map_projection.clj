(ns skeptic.analysis.annotate.map-projection
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.origin.schema :as aos]))

(defn- local-node-key
  [node]
  [(:op node)
   (:form node)
   (some-> node :binding-init :op)
   (some-> node :binding-init :form)])

(defn- prior-local-alias
  [local-node]
  (let [sym (:form local-node)
        init (:binding-init local-node)]
    (cond
      (= :local (:op init))
      init

      init
      (some (fn [node]
              (when (and (= :local (:op node))
                         (= sym (:form node))
                         (:binding-init node))
                node))
            (rest (sac/ast-nodes init)))

      :else nil)))

(s/defn projection-root-local
  [target-node :- s/Any]
  :- (s/maybe aos/Origin)
  (loop [node target-node
         seen #{}]
    (when (aapi/stable-identity-node? node)
      (let [k (local-node-key node)]
        (if (contains? seen k)
          node
          (if-let [alias (prior-local-alias node)]
            (recur alias (conj seen k))
            node))))))

(s/defn projection-root-origin
  [ctx :- s/Any, target-node :- s/Any]
  :- (s/maybe aos/Origin)
  (when-let [root-local (projection-root-local target-node)]
    (or (ao/local-root-origin ctx root-local)
        (ao/root-origin (:form root-local) (or (:type root-local) (aapi/dyn ctx))))))

(s/defn map-key-lookup-origin
  [ctx :- s/Any, target-node :- s/Any, key-query :- s/Any, default-type :- s/Any]
  :- (s/maybe aos/Origin)
  (let [target-origin (aapi/node-origin target-node)]
    (if (= :map-key-lookup (:kind target-origin))
      {:kind :map-key-lookup
       :root (:root target-origin)
       :path (conj (:path target-origin) key-query)
       :defaults (conj (:defaults target-origin) default-type)}
      (when-let [root (projection-root-origin ctx target-node)]
        {:kind :map-key-lookup
         :root root
         :path [key-query]
         :defaults [default-type]}))))
