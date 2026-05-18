(ns skeptic.analysis.annotate.runner-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.annotate.runner :as runner]))

(defn- identity-helper [_ctx node] (runner/done node))

(deftest run-done-returns-value
  (is (= :leaf (runner/run identity-helper {} :leaf))))

(deftest run-call-step-then-done
  (let [helper (fn [_ctx node]
                 (runner/call identity-helper {} node
                              (fn [v] (runner/done [:wrapped v]))))]
    (is (= [:wrapped 42] (runner/run helper {} 42)))))

(deftest run-nested-calls
  (let [inner (fn [_ctx node] (runner/done (inc node)))
        outer (fn [_ctx node]
                (runner/call inner {} node
                             (fn [a]
                               (runner/call inner {} a
                                            (fn [b] (runner/done b))))))]
    (is (= 3 (runner/run outer {} 1)))))

(deftest run-auto-wraps-non-step-values
  (let [legacy (fn [_ctx node] (inc node))]
    (is (= 5 (runner/run legacy {} 4)))))

(deftest sequence-children-collects-in-order
  (let [recurse-step (fn [_ctx n] (runner/done (* n 10)))
        ctx {:recurse-step recurse-step}
        entry (fn [_ctx _node]
                (runner/sequence-children ctx [1 2 3] runner/done))]
    (is (= [10 20 30] (runner/run entry ctx nil)))))

(deftest reduce-children-threads-state-and-ctx
  (let [recurse-step (fn [_ctx n] (runner/done n))
        ctx {:recurse-step recurse-step}
        step-fn (fn [state _ctx _child annotated]
                  [(+ state annotated) ctx])
        entry (fn [_ctx _node]
                (runner/reduce-children ctx 0 [1 2 3 4] step-fn
                                        (fn [final-state acc]
                                          (runner/done {:sum final-state :seq acc}))))]
    (is (= {:sum 10 :seq [1 2 3 4]} (runner/run entry ctx nil)))))
