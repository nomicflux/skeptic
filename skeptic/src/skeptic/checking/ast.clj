(ns skeptic.checking.ast
  (:require [clojure.tools.analyzer.ast :as ana.ast]
            [skeptic.analysis.calls :as ac]
            [skeptic.checking.form :as cf]))

(def invoke-ops
  #{:instance-call
    :invoke
    :keyword-invoke
    :prim-invoke
    :protocol-invoke
    :static-call})

(defn distinctv
  [xs]
  (reduce (fn [acc x]
            (if (some #(= % x) acc)
              acc
              (conj acc x)))
          []
          xs))

(defn child-nodes
  [node]
  (mapcat (fn [child]
            (let [value (get node child)]
              (cond
                (vector? value) value
                (map? value) [value]
                :else [])))
          (:children node)))

(defn ast-nodes-preorder
  [ast]
  (tree-seq map? child-nodes ast))

(defn node-ref
  [node]
  (when node
    (select-keys node [:form :type])))

(defn callee-ref
  [node]
  (when node
    (case (:op node)
      :invoke (node-ref (:fn node))
      :with-meta (recur (:expr node))
      nil)))

(defn match-up-arglists
  [arg-nodes expected actual]
  (cf/spy :match-up-actual-list actual)
  (cf/spy :match-up-expected-list expected)
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(get arg-nodes n)
       (cf/spy :match-up-expected (get expected n expected-vararg))
       (cf/spy :match-up-actual (get actual n))])))

(defn binding-index
  [ast]
  (reduce (fn [acc node]
            (if (= :binding (:op node))
              (assoc acc (:form node) node)
              acc))
          {}
          (ana.ast/nodes ast)))

(defn local-resolution-path
  [bindings local-node]
  (if-let [binding (get bindings (:form local-node))]
    (if-let [init (:init binding)]
      (cond-> [(node-ref init)]
        (callee-ref init)
        (conj (callee-ref init)))
      [])
    []))

(defn local-vars-context
  [bindings node]
  (->> (ana.ast/nodes node)
       (filter #(= :local (:op %)))
       (reduce (fn [acc local-node]
                 (if (contains? acc (:form local-node))
                   acc
                   (assoc acc
                          (:form local-node)
                          {:form (:form local-node)
                           :type (:type local-node)
                           :resolution-path (local-resolution-path bindings local-node)})))
               {})))

(defn call-refs
  [bindings node]
  (let [fn-node (:fn node)]
    (cond
      (nil? fn-node) []
      (= :local (:op fn-node))
      (into [(node-ref fn-node)]
            (local-resolution-path bindings fn-node))
      :else
      (cond-> []
        (node-ref fn-node)
        (conj (node-ref fn-node))))))

(defn call-node?
  [node]
  (and (contains? invoke-ops (:op node))
       (vector? (:args node))
       (seq (:expected-argtypes node))
       (seq (:actual-argtypes node))))

(defn dict-entry
  [dict ns-sym sym]
  (or (get dict sym)
      (get dict (ac/qualify-symbol ns-sym sym))))

(defn unwrap-with-meta
  [node]
  (if (= :with-meta (:op node))
    (recur (:expr node))
    node))
