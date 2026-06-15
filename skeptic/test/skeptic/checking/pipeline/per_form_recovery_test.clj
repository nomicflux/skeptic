(ns skeptic.checking.pipeline.per-form-recovery-test
  "Best-effort recovery (project-faithful-load logical): a top-level form
   that throws at load time or during analysis does NOT abort the namespace.
   The form is demoted via the existing :analysis-skipped path; the rest of
   the namespace is still checked. The `ns` form is the documented exception:
   a failure there aborts the namespace with one :exception finding."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skeptic.analysis.class-oracle :as class-oracle]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.checking.pipeline.support :as psup])
  (:import [java.io File]))

(use-fixtures :once psup/with-worker)

(def ^:private fixture-dir
  (File. "dev-resources/skeptic/clj_fixtures/per_form_recovery"))

(defn- file-for [filename] (File. fixture-dir filename))

(defn- exceptions-of [results]
  (filterv #(= :exception (:report-kind %)) results))

(defn- analysis-skipped-of [results]
  (filterv #(= :analysis-skipped (:report-kind %)) results))

(defn- check-one [ns-sym source-file]
  (let [ps (pipeline/project-state {:worker-conn class-oracle/*worker-conn*}
                                   [[ns-sym source-file]])]
    (pipeline/check-namespace ps ns-sym source-file {:remove-context true})))

(defn- check-two [primary-ns primary-file second-ns second-file]
  (let [ps (pipeline/project-state {:worker-conn class-oracle/*worker-conn*}
                                   [[primary-ns primary-file]
                                    [second-ns second-file]])]
    {:primary (pipeline/check-namespace ps primary-ns primary-file
                                        {:remove-context true})
     :second  (pipeline/check-namespace ps second-ns second-file
                                        {:remove-context true})}))

(deftest t1-private-var-consumer-yields-skipped-finding-and-continues
  (let [owner-ns 'skeptic.clj-fixtures.per-form-recovery.private-owner
        consumer-ns 'skeptic.clj-fixtures.per-form-recovery.private-consumer
        owner-file (file-for "private_owner.clj")
        consumer-file (file-for "private_consumer.clj")
        {:keys [primary]} (check-two consumer-ns consumer-file owner-ns owner-file)
        skipped (analysis-skipped-of (:results primary))]
    (testing "exactly one analysis-skipped finding is emitted"
      (is (= 1 (count skipped)))
      (is (str/includes? (str (:exception-message (first skipped)))
                         "not public")))
    (testing "the rest of the namespace is still analyzed (no namespace abort)"
      (is (empty? (exceptions-of (:results primary)))
          "the namespace must not produce a wholesale abort exception finding"))))

(deftest t2-def-throws-then-reference
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.def-throws
        source-file (file-for "def_throws.clj")
        results (:results (check-one ns-sym source-file))
        skipped (analysis-skipped-of results)]
    (is (<= 1 (count skipped)))
    (is (some #(str/includes? (str (:exception-message %)) "boom-def") skipped))))

(deftest t3-bare-side-effecting-throw
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.bare-throws
        source-file (file-for "bare_throws.clj")
        results (:results (check-one ns-sym source-file))
        skipped (analysis-skipped-of results)
        finding (first skipped)]
    (is (= 1 (count skipped)))
    (is (str/includes? (str (:exception-message finding)) "bare-boom"))
    (is (= :analysis-skipped (:report-kind finding)))))

(deftest t4-ns-form-failure-aborts-the-namespace
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.ns-throws
        source-file (file-for "ns_throws.clj")
        results (:results (check-one ns-sym source-file))
        exceptions (exceptions-of results)
        skipped (analysis-skipped-of results)]
    (is (= 1 (count exceptions))
        "the failing ns form aborts the namespace with one finding")
    (is (empty? skipped)
        "ns-form abort is the namespace-level path, not per-form recovery")))

(deftest t5-two-consecutive-failures-then-healthy-form
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.two-consecutive
        source-file (file-for "two_consecutive.clj")
        results (:results (check-one ns-sym source-file))
        skipped (analysis-skipped-of results)
        messages (map (comp str :exception-message) skipped)]
    (is (= 2 (count skipped)))
    (is (some #(str/includes? % "first-boom") messages))
    (is (some #(str/includes? % "second-boom") messages))))

(deftest t6-throwable-not-exception-is-caught
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.throwable-error
        source-file (file-for "throwable_error.clj")
        results (:results (check-one ns-sym source-file))
        skipped (analysis-skipped-of results)]
    (is (= 1 (count skipped)))
    (is (str/includes? (str (:exception-message (first skipped)))
                       "error-subclass-boom"))))
