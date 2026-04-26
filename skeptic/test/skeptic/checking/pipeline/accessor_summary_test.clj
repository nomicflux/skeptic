(ns skeptic.checking.pipeline.accessor-summary-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis-test :as atst]
            [skeptic.checking.pipeline :as pipeline]))

(defn- summary-of
  [form]
  (:summary (pipeline/analyzed-def-entry 'skeptic.analysis-test
                                         (atst/analyze-form form))))

(deftest direct-keyword-invoke-classifier
  (let [s (summary-of '(defn classify [m]
                         (case (:k m) "a" :a "b" :b :unclassified)))]
    (is (= :unary-map-classifier (:kind s)))
    (is (= [:k] (mapv :value (:path s))))
    (is (= {"a" :a "b" :b} (:cases s)))
    (is (= :unclassified (:default s)))))

(deftest static-get-classifier
  (let [s (summary-of '(defn classify [m]
                         (case (clojure.lang.RT/get m :k) "a" :a "b" :b :unclassified)))]
    (is (= :unary-map-classifier (:kind s)))
    (is (= [:k] (mapv :value (:path s))))))

(deftest destructured-classifier
  (let [s (summary-of '(defn classify [{:keys [k]}]
                         (case k "a" :a "b" :b :unclassified)))]
    (is (= :unary-map-classifier (:kind s)))
    (is (= [:k] (mapv :value (:path s))))))

(deftest plain-get-classifier-via-inline
  (let [s (summary-of '(defn classify [m]
                         (case (get m :k) "a" :a "b" :b :unclassified)))]
    (is (= :unary-map-classifier (:kind s)))
    (is (= [:k] (mapv :value (:path s))))))

(deftest different-classifier-name-recognized-identically
  (let [s (summary-of '(defn another-classifier [m]
                         (case (:k m) "a" :a "b" :b :unclassified)))]
    (is (= :unary-map-classifier (:kind s)))))

(deftest non-classifier-still-recognized-as-accessor
  (let [s (summary-of '(defn k-get [m] (:k m)))]
    (is (= :unary-map-accessor (:kind s)))
    (is (= :k (:kw s)))))
