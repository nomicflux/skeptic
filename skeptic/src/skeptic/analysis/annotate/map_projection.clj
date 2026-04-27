(ns skeptic.analysis.annotate.map-projection
  (:require [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.origin :as ao]))

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

(defn projection-root-local
  [target-node]
  (loop [node target-node
         seen #{}]
    (when (aapi/stable-identity-node? node)
      (let [k (local-node-key node)]
        (if (contains? seen k)
          node
          (if-let [alias (prior-local-alias node)]
            (recur alias (conj seen k))
            node))))))

(defn projection-root-origin
  [ctx target-node]
  (when-let [root-local (projection-root-local target-node)]
    (or (ao/local-root-origin ctx root-local)
        (ao/root-origin (:form root-local) (or (:type root-local) (aapi/dyn ctx))))))

(defn map-key-lookup-origin
  [ctx target-node key-query default-type]
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
