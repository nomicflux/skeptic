(ns skeptic.analysis.annotate.runner-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.annotate.runner :as runner]))

(defn- nd
  ([op] {:op op :form op})
  ([op extra] (merge {:op op :form op} extra)))

(defn- identity-helper [_ctx node] (runner/done node))

(deftest run-done-returns-node
  (is (= (nd :leaf)
         (runner/run identity-helper {} (nd :leaf)))))

(deftest run-call-step-then-done
  (let [helper (fn [_ctx node]
                 (runner/call identity-helper {} node
                              (fn [v] (runner/done (assoc v :wrapped true)))))]
    (is (true? (:wrapped (runner/run helper {} (nd :x)))))))

(deftest run-nested-calls
  (let [bump (fn [_ctx node]
               (runner/done (update node :count (fnil inc 0))))
        outer (fn [_ctx node]
                (runner/call bump {} node
                             (fn [a]
                               (runner/call bump {} a
                                            (fn [b] (runner/done b))))))]
    (is (= 2 (:count (runner/run outer {} (nd :n)))))))

(deftest run-with-finalizer-finalizes-root
  (let [result (runner/run-with-finalizer
                identity-helper {:phase :root} (nd :leaf)
                (fn [helper-fn ctx node value]
                  (assoc value
                         :helper? (identical? helper-fn identity-helper)
                         :ctx-phase (:phase ctx)
                         :node-op (:op node))))]
    (is (= (assoc (nd :leaf)
                  :helper? true
                  :ctx-phase :root
                  :node-op :leaf)
           result))))

(deftest run-with-finalizer-finalizes-nested-helper-before-parent-k
  (let [target-helper (fn [_ctx node]
                        (runner/done (assoc node :target true)))
        passthrough-helper (fn [_ctx node]
                             (runner/done (assoc node :passthrough true)))
        outer (fn [_ctx node]
                (runner/call passthrough-helper {} (nd :direct)
                             (fn [direct]
                               (runner/call target-helper {:phase :child} (nd :child)
                                            (fn [child]
                                              (runner/done
                                               (assoc node
                                                      :direct direct
                                                      :child child)))))))
        result (runner/run-with-finalizer
                outer {} (nd :parent)
                (fn [helper-fn ctx _node value]
                  (if (identical? helper-fn target-helper)
                    (assoc value :finalized (:phase ctx))
                    value)))]
    (is (= (assoc (nd :direct) :passthrough true)
           (:direct result)))
    (is (= (assoc (nd :child) :target true :finalized :child)
           (:child result)))
    (is (nil? (:finalized result)))))

(deftest run-auto-wraps-non-step-helper-returns
  ;; Migration-window behavior. Phase 7 contracts the runner — every
  ;; helper returns Step there, and this test goes away with the auto-wrap.
  (let [legacy (fn [_ctx node] (assoc node :touched true))]
    (is (true? (:touched (runner/run legacy {} (nd :v)))))))

(deftest sequence-children-collects-in-order
  (let [recurse-step (fn [_ctx n] (runner/done (assoc n :marked true)))
        children [(nd :c1) (nd :c2) (nd :c3)]
        entry (fn [_ctx node]
                (runner/sequence-children
                 {:recurse-step recurse-step}
                 children
                 (fn [annotated]
                   (runner/done (assoc node :items annotated)))))
        result (runner/run entry {:recurse-step recurse-step} (nd :parent))]
    (is (= 3 (count (:items result))))
    (is (every? :marked (:items result)))))

(deftest reduce-children-threads-state-and-ctx
  (let [recurse-step (fn [_ctx n] (runner/done n))
        children [(nd :c1) (nd :c2) (nd :c3) (nd :c4)]
        step-fn  (fn [state ctx _child _annotated] [(inc state) ctx])
        entry (fn [_ctx node]
                (runner/reduce-children
                 {:recurse-step recurse-step}
                 0 children step-fn
                 (fn [final-state acc]
                   (runner/done (assoc node :count final-state :items acc)))))
        result (runner/run entry {:recurse-step recurse-step} (nd :root))]
    (is (= 4 (:count result)))
    (is (= 4 (count (:items result))))))
