(ns skeptic.checking.pipeline.best-effort-test
  "One bad namespace never costs the others: a namespace that fails to load
   surfaces as a single loud exception finding carrying the real cause, and
   every other namespace in the same project-state still gets full analysis."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skeptic.analysis.class-oracle :as class-oracle]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.checking.pipeline.support :as psup])
  (:import [java.io File]))

(use-fixtures :once psup/with-worker)

(def ^:private fixture-dir
  (File. "dev-resources/skeptic/clj_fixtures/best_effort"))

(def ^:private broken-ns 'skeptic.clj-fixtures.best-effort.broken)
(def ^:private healthy-ns 'skeptic.clj-fixtures.best-effort.healthy)
(def ^:private broken-file (File. fixture-dir "broken.clj"))
(def ^:private healthy-file (File. fixture-dir "healthy.clj"))

(deftest one-broken-namespace-does-not-cost-the-others
  (let [ps (pipeline/project-state {:worker-conn class-oracle/*worker-conn*}
                                   {broken-ns broken-file
                                    healthy-ns healthy-file})
        broken (pipeline/check-namespace ps broken-ns broken-file
                                         {:remove-context true})
        healthy (pipeline/check-namespace ps healthy-ns healthy-file
                                          {:remove-context true})]
    (testing "the broken namespace yields one exception finding with the cause"
      (let [exceptions (filterv #(= :exception (:report-kind %)) (:results broken))]
        (is (= 1 (count exceptions)))
        (is (str/includes? (str (:exception-message (first exceptions)))
                           "boom at load time"))))
    (testing "the healthy namespace is still fully checked"
      (is (empty? (filter #(= :exception (:report-kind %)) (:results healthy))))
      (is (seq (filter #(= :input (:report-kind %)) (:results healthy)))
          "the healthy fixture's schema mismatch must still be found"))))
