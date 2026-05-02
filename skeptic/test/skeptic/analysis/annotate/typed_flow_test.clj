(ns skeptic.analysis.annotate.typed-flow-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis-test :as atst]
            [skeptic.test-helpers :refer [is-type= T]]))

(deftest typed-flow-through-let-and-if-test
  (testing "local bindings feed refined branch outputs"
    (let [ast (atst/analyze-form '(let [x nil] (if x x 1)))]
      (is (= :let (aapi/node-op ast)))
      (is-type= (T s/Int) (aapi/node-type ast))))
  (testing "do returns the final expression type"
    (let [ast (atst/analyze-form '(do (str "x") 1))]
      (is (= :do (aapi/node-op ast)))
      (is-type= (T s/Int) (aapi/node-type ast)))))

(deftest typed-function-flow-test
  (let [ast (atst/analyze-form '(fn [x] x) {:name 'skeptic.analysis-test/f})
        method (first (aapi/function-methods ast))]
    (is-type= (T s/Any) (aapi/node-output-type ast))
    (let [arglist (aapi/arglist-types ast 1)]
      (is (= 1 (count arglist)))
      (is-type= (T s/Any) (first arglist)))
    (is-type= (T s/Any) (-> method aapi/method-result-type :output-type))))
