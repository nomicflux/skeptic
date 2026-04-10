(ns skeptic.analysis.annotate.analyse-detail-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis-test :as atst]))

(deftest detailed-let-and-if-shape-test
  (let [ast (atst/analyze-form '(let [x 1] (if x x 2)))
        projected (aat/project-ast ast)
        if-node (aat/find-projected-node projected #(= :if (:op %)))]
    (is (= :let (:op projected)))
    (is (= :binding (:op (first (aat/child-projection projected :bindings)))))
    (is (= :if (:op if-node)))
    (is (= '(if x x 2) (:form if-node)))))

(deftest detailed-def-and-fn-shape-test
  (testing "defn produces def with wrapped fn init"
    (let [ast (aat/annotate-form-loop {} '(defn sample [x] x) {:ns 'user})]
      (is (= :def (aapi/node-op ast)))
      (is (= 'sample (aapi/node-name ast)))
      (is (= :with-meta (aapi/node-op (aapi/def-init-node ast))))
      (is (= [(atst/T s/Any)] (aapi/arglist-types (aapi/unwrap-with-meta (aapi/def-init-node ast)) 1))))))
