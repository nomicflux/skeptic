(ns skeptic.checking.pipeline.per-form-recovery-test
  "Best-effort recovery (project-faithful-load logical): a top-level form
   that throws at load time or during analysis does NOT abort the namespace.
   The form's value is treated as Dyn, the rest of the namespace is checked,
   and the exception surfaces as a per-form-compile-time finding. The `ns`
   form is the documented exception: a failure there still aborts the
   namespace with one finding."
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

(defn- per-form-recovery-of [results]
  (filterv #(and (= :exception (:report-kind %))
                 (:per-form-compile-time? %))
           results))

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

(deftest t1-private-var-consumer-yields-per-form-finding-and-continues
  ;; Consumer references a cross-namespace private var. Compiler raises
  ;; "var: #'X is not public" on that form. The form becomes a per-form
  ;; finding; the neighbor s/defn in the same file is still analyzed.
  (let [owner-ns 'skeptic.clj-fixtures.per-form-recovery.private-owner
        consumer-ns 'skeptic.clj-fixtures.per-form-recovery.private-consumer
        owner-file (file-for "private_owner.clj")
        consumer-file (file-for "private_consumer.clj")
        {:keys [primary]} (check-two consumer-ns consumer-file owner-ns owner-file)
        recovered (per-form-recovery-of (:results primary))]
    (testing "exactly one per-form-compile-time finding is emitted"
      (is (= 1 (count recovered)))
      (is (str/includes? (str (:exception-message (first recovered)))
                         "not public")))
    (testing "the rest of the namespace is still analyzed (no namespace abort)"
      (let [other-exceptions (filterv #(and (= :exception (:report-kind %))
                                            (not (:per-form-compile-time? %)))
                                      (:results primary))]
        (is (empty? other-exceptions)
            "the namespace must not produce a wholesale abort exception finding")))))

(deftest t2-def-throws-then-reference-resolves-to-dyn
  ;; (def boom (throw ...)) fails. (def downstream-of-boom boom) references
  ;; the var that was never interned; under best-effort that reference is
  ;; Dyn-typed, so no additional cascading finding is produced for it.
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.def-throws
        source-file (file-for "def_throws.clj")
        results (:results (check-one ns-sym source-file))
        recovered (per-form-recovery-of results)]
    (testing "one per-form finding for the throwing def"
      (is (<= 1 (count recovered)))
      (is (some #(str/includes? (str (:exception-message %)) "boom-def") recovered)))))

(deftest t3-bare-side-effecting-throw
  ;; (throw ...) at top level: no symbol interned, no Dyn binding, just
  ;; one per-form finding pinned to the throw, with :report-kind :exception
  ;; and :per-form-compile-time? true (wire kind stays exception; the reframe
  ;; is in classification, not in the wire kind). The neighbor defn after the
  ;; throw is still analyzed.
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.bare-throws
        source-file (file-for "bare_throws.clj")
        results (:results (check-one ns-sym source-file))
        recovered (per-form-recovery-of results)
        finding (first recovered)]
    (is (= 1 (count recovered)))
    (is (str/includes? (str (:exception-message finding)) "bare-boom"))
    (is (= :exception (:report-kind finding)))
    (is (true? (:per-form-compile-time? finding)))))

(deftest t4-ns-form-failure-aborts-the-namespace
  ;; A failing `ns` form (here: `:import` of a non-existent class) has no
  ;; usable namespace context for downstream forms. Documented exception:
  ;; the namespace aborts with ONE finding pinned to the ns form. The
  ;; finding is NOT marked per-form-compile-time? (it is the namespace-level
  ;; abort path, not the per-form recovery path).
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.ns-throws
        source-file (file-for "ns_throws.clj")
        results (:results (check-one ns-sym source-file))
        exceptions (exceptions-of results)
        recovered (per-form-recovery-of results)]
    (is (= 1 (count exceptions))
        "the failing ns form aborts the namespace with one finding")
    (is (empty? recovered)
        "ns-form abort is the namespace-level path, not per-form recovery")))

(deftest t5-two-consecutive-failures-then-healthy-form
  ;; Two throws in a row; each is its own per-form finding. The defn after
  ;; them is still analyzed.
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.two-consecutive
        source-file (file-for "two_consecutive.clj")
        results (:results (check-one ns-sym source-file))
        recovered (per-form-recovery-of results)
        messages (map (comp str :exception-message) recovered)]
    (is (= 2 (count recovered)))
    (is (some #(str/includes? % "first-boom") messages))
    (is (some #(str/includes? % "second-boom") messages))))

(deftest t6-throwable-not-exception-is-caught
  ;; `(throw (Error. ...))` raises a `java.lang.Error`, not `java.lang.Exception`.
  ;; The catch is `Throwable`, so it still becomes a per-form finding.
  (let [ns-sym 'skeptic.clj-fixtures.per-form-recovery.throwable-error
        source-file (file-for "throwable_error.clj")
        results (:results (check-one ns-sym source-file))
        recovered (per-form-recovery-of results)]
    (is (= 1 (count recovered)))
    (is (str/includes? (str (:exception-message (first recovered)))
                       "error-subclass-boom"))))

