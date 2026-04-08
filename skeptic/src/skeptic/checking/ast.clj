(ns skeptic.checking.ast
  (:require [skeptic.analysis.ast-children :as sac]
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

(defn local-resolution-path
  [local-node]
  (let [init (:binding-init local-node)]
    (if init
      (cond-> [(node-ref init)]
        (callee-ref init)
        (conj (callee-ref init)))
      [])))

(defn local-vars-context
  [node]
  (->> (sac/ast-nodes node)
       (filter #(= :local (:op %)))
       (reduce (fn [acc local-node]
                 (if (contains? acc (:form local-node))
                   acc
                   (assoc acc
                          (:form local-node)
                          {:form (:form local-node)
                           :type (:type local-node)
                           :resolution-path (local-resolution-path local-node)})))
               {})))

(defn call-refs
  [node]
  (let [fn-node (:fn node)]
    (cond
      (nil? fn-node) []
      (= :local (:op fn-node))
      (into [(node-ref fn-node)]
            (local-resolution-path fn-node))
      :else
      (cond-> []
        (node-ref fn-node)
        (conj (node-ref fn-node))))))

(defn call-node?
  [node]
  (or (and (contains? invoke-ops (:op node))
           (vector? (:args node))
           (seq (:expected-argtypes node))
           (seq (:actual-argtypes node)))
      (and (= :recur (:op node))
           (vector? (:exprs node))
           (seq (:expected-argtypes node))
           (seq (:actual-argtypes node)))))

(defn dict-entry
  [dict ns-sym sym]
  (or (get dict sym)
      (get dict (ac/qualify-symbol ns-sym sym))))

(defn unwrap-with-meta
  [node]
  (if (= :with-meta (:op node))
    (recur (:expr node))
    node))
