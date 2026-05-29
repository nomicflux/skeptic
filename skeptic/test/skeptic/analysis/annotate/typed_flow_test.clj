(ns skeptic.analysis.annotate.typed-flow-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis-test :as atst]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-helpers :refer [is-type= T]]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def ^:private sc-dict (catalog/typed-test-example-entries))
(def ^:private sc-ns 'skeptic.test-examples.structural-cases)

(defn- sc-def [name]
  (atst/ast-by-name (aat/analyze-ns-file sc-dict sc-ns (atst/fixture-file-for-ns sc-ns) {}) name))

(defn- sc-body [name]
  (aapi/method-body (first (aapi/def-fn-methods (sc-def name)))))

(deftest typed-flow-through-let-and-if-test
  (testing "local bindings feed refined branch outputs"
    (let [ast (sc-body 'sc-let-if)]
      (is (= :let (aapi/node-op ast)))
      (is-type= (T s/Int) (aapi/node-type ast))))
  (testing "do returns the final expression type"
    (let [ast (sc-body 'sc-do-final)]
      (is (= :do (aapi/node-op ast)))
      (is-type= (T s/Int) (aapi/node-type ast)))))

(deftest typed-function-flow-test
  (let [ast (aapi/def-value-node (sc-def 'sc-identity-fn))
        method (first (aapi/function-methods ast))]
    (is-type= (T s/Any) (aapi/node-output-type ast))
    (let [arglist (aapi/arglist-types ast 1)]
      (is (= 1 (count arglist)))
      (is-type= (T s/Any) (first arglist)))
    (is-type= (T s/Any) (-> method aapi/method-result-type :output-type))))
