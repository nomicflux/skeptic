(ns skeptic.analysis.annotate.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.types :as at]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-helpers :refer [is-type= T]]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def ^:private sc-dict (catalog/typed-test-example-entries))
(def ^:private sc-ns 'skeptic.test-examples.structural-cases)

(defn- sc-body [name]
  (let [asts (aat/analyze-ns-file sc-dict sc-ns (atst/fixture-file-for-ns sc-ns) {})
        def-node (atst/ast-by-name asts name)]
    (aapi/method-body (first (aapi/def-fn-methods def-node)))))

(deftest annotate-form-loop-integration-test
  (testing "annotation stays first-order and keeps typed call metadata"
    (let [ast (sc-body 'sc-known-int-add)]
      (is (= :invoke (aapi/node-op ast)))
      (let [args (aapi/call-actual-argtypes ast)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Int) (nth args 1)))
      (let [args (aapi/call-expected-argtypes ast)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Int) (nth args 1)))
      (is (aapi/typed-call-metadata-only? ast))
      (is (not-any? at/forall-type? (keep aapi/node-type (aapi/annotated-nodes ast))))
      (is (not-any? at/type-var-type? (keep aapi/node-type (aapi/annotated-nodes ast))))
      (is (not-any? at/sealed-dyn-type? (keep aapi/node-type (aapi/annotated-nodes ast)))))))

(deftest integration-preserves-local-invocation
  (let [ast (sc-body 'sc-local-fn-invoke)
        call-node (aapi/find-node ast #(= '(f 1) (aapi/node-form %)))]
    (is (some? (aapi/node-type ast)))
    (is (some? (aapi/node-type call-node)))
    (is (aapi/typed-call-metadata-only? call-node))))
