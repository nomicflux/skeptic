(ns skeptic.analysis.annotate.analyse-detail-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.test-helpers :refer [is-type= T some!]]
            [skeptic.analysis-test :as atst]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def ^:private sc-dict (catalog/typed-test-example-entries))
(def ^:private sc-ns 'skeptic.test-examples.structural-cases)

(defn- sc-def [name]
  (atst/ast-by-name (aat/analyze-ns-file sc-dict sc-ns (atst/fixture-file-for-ns sc-ns) {}) name))

(deftest detailed-let-and-if-shape-test
  (let [ast (aapi/method-body (first (aapi/def-fn-methods (sc-def 'sc-let-if-shape))))
        projected (aat/project-ast ast)
        if-node (aat/find-projected-node projected #(= :if (:op %)))]
    (is (= :let (:op projected)))
    (is (= :binding (:op (first (aat/child-projection projected :bindings)))))
    (is (= :if (:op if-node)))
    (is (= '(if x x 2) (:form if-node)))))

(deftest detailed-def-and-fn-shape-test
  (testing "defn produces def with wrapped fn init"
    (let [ast (sc-def 'sc-detail-sample)
          init-node (some! (aapi/def-init-node ast))]
      (is (= :def (aapi/node-op ast)))
      (is (= 'sc-detail-sample (aapi/node-name ast)))
      (is (= :with-meta (aapi/node-op init-node)))
      (let [arglist (aapi/arglist-types (aapi/unwrap-with-meta init-node) 1)]
        (is (= 1 (count arglist)))
        (is-type= (T s/Any) (first arglist))))))
