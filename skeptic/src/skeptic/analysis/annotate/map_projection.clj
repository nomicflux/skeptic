(ns skeptic.analysis.annotate.map-projection
  (:require [skeptic.analysis.ast-children :as sac]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.types :as at]))

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
    (when (= :local (:op node))
      (let [k (local-node-key node)]
        (if (contains? seen k)
          node
          (if-let [alias (prior-local-alias node)]
            (recur alias (conj seen k))
            node))))))

(defn projection-root-origin
  [target-node]
  (when-let [root-local (projection-root-local target-node)]
    (or (ao/local-root-origin root-local)
        (ao/root-origin (:form root-local) (or (:type root-local) at/Dyn)))))

(defn map-key-lookup-origin
  [target-node key-query]
  (when-let [root (projection-root-origin target-node)]
    {:kind :map-key-lookup
     :root root
     :key-query key-query}))
