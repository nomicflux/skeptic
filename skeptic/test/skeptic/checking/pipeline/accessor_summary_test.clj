(ns skeptic.checking.pipeline.accessor-summary-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis-test :as atst]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def ^:private ac-dict (catalog/typed-test-example-entries))
(def ^:private ac-ns 'skeptic.test-examples.accessor-cases)

(defn- summary-of
  "Worker-analyzes the accessor-cases fixture ns and returns the accessor
   `:summary` of fixture def `name`."
  [name]
  (let [asts (aat/analyze-ns-file ac-dict ac-ns (atst/fixture-file-for-ns ac-ns) {})
        def-node (atst/ast-by-name asts name)]
    (:summary (pipeline/analyzed-def-entry ac-ns def-node))))

(deftest direct-keyword-invoke-classifier
  (let [s (summary-of 'ac-classify-kw-invoke)]
    (is (= :unary-map-projection (:kind s)))
    (is (= [:k] (mapv :value (:path s))))
    (is (= {"a" :a "b" :b} (:cases s)))
    (is (= :unclassified (:default s)))))

(deftest static-get-classifier
  (let [s (summary-of 'ac-classify-static-get)]
    (is (= :unary-map-projection (:kind s)))
    (is (= [:k] (mapv :value (:path s))))))

(deftest destructured-classifier
  (let [s (summary-of 'ac-classify-destructured)]
    (is (= :unary-map-projection (:kind s)))
    (is (= [:k] (mapv :value (:path s))))))

(deftest plain-get-classifier-via-inline
  (let [s (summary-of 'ac-classify-plain-get)]
    (is (= :unary-map-projection (:kind s)))
    (is (= [:k] (mapv :value (:path s))))))

(deftest keyword-get-default-classifier
  (let [s (summary-of 'ac-choose-kw-get-default)]
    (is (= :unary-map-projection (:kind s)))
    (is (= [:k] (mapv :value (:path s))))
    (is (= :a (:default s)))
    (is (= :keyword (:result-transform s)))))

(deftest different-classifier-name-recognized-identically
  (let [s (summary-of 'ac-another-classifier)]
    (is (= :unary-map-projection (:kind s)))))

(deftest non-classifier-still-recognized-as-accessor
  (let [s (summary-of 'ac-k-get)]
    (is (= :unary-map-projection (:kind s)))
    (is (= :k (-> s :path first :value)))
    (is (not (contains? s :values)))))
