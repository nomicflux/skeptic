(ns skeptic.test-examples.structural-cases
  "Bare-form fixtures for structural-test, typed-flow-test, analyse-detail-test,
   integration-test, and ast-test. Each former `(analyze-form '(form))` probe
   (no injected locals) becomes a fixture def whose body is that form, analyzed
   by the worker; the test selects the def's value-node body."
  (:require [schema.core :as s]
            [skeptic.test-examples.basics :as basics]))

;; structural-throw-try-and-loop-test
(s/defn sc-throw [] (throw (Exception. "boom")))
(s/defn sc-try-catch [] (try 1 (catch Exception e e)))
(s/defn sc-loop-recur [] (loop [x 0] (recur (inc x))))

;; structural-literal-collections-test
(s/defn sc-vec-literal [] [1 2])
(s/defn sc-map-literal [] {:a 1})
(s/defn sc-set-literal [] #{1 2})

;; typed-flow-through-let-and-if-test
(s/defn sc-let-if [] (let [x nil] (if x x 1)))
(s/defn sc-do-final [] (do (str "x") 1))

;; typed-function-flow-test (selects the inner :fn via def-init-node)
(s/defn sc-identity-fn [x] x)

;; detailed-let-and-if-shape-test
(s/defn sc-let-if-shape [] (let [x 1] (if x x 2)))

;; detailed-def-and-fn-shape-test (plain defn, selected as the def node itself)
(defn sc-detail-sample [x] x)

;; integration-test
(s/defn sc-known-int-add [] (basics/int-add 1 2))
(s/defn sc-local-fn-invoke [] (let [f (fn [x] x)] (f 1)))

;; ast-test local-vars-context
(s/defn sc-let-x [] (let [x 1] x))
