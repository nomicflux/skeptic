(ns skeptic.analysis.annotate.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.types :as at]))

(deftest annotate-form-loop-integration-test
  (testing "annotation stays first-order and keeps typed call metadata"
    (let [ast (aat/annotate-form-loop atst/analysis-dict
                                      '(skeptic.test-examples.basics/int-add 1 2)
                                      {:ns 'skeptic.analysis-test})]
      (is (= :invoke (aapi/node-op ast)))
      (is (= [(atst/T s/Int) (atst/T s/Int)] (aapi/call-actual-argtypes ast)))
      (is (= [(atst/T s/Int) (atst/T s/Int)] (aapi/call-expected-argtypes ast)))
      (is (aapi/typed-call-metadata-only? ast))
      (is (not-any? at/forall-type? (keep aapi/node-type (aapi/annotated-nodes ast))))
      (is (not-any? at/type-var-type? (keep aapi/node-type (aapi/annotated-nodes ast))))
      (is (not-any? at/sealed-dyn-type? (keep aapi/node-type (aapi/annotated-nodes ast)))))))

(deftest integration-preserves-local-invocation
  (let [ast (atst/analyze-form '(let [f (fn [x] x)] (f 1)))
        call-node (aapi/find-node ast #(= '(f 1) (aapi/node-form %)))]
    (is (some? (aapi/node-type ast)))
    (is (some? (aapi/node-type call-node)))
    (is (aapi/typed-call-metadata-only? call-node))))
