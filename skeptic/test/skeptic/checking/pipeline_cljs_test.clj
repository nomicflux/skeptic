(ns skeptic.checking.pipeline-cljs-test
  "Phase 7 smoke gate: a mixed .clj / .cljs / .cljc project produces findings
  whose `:lang` attribution matches the source language. .cljc files run
  both passes and dedup identical findings to `:lang #{:clj :cljs}`."
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.checking.pipeline :as pipeline])
  (:import [java.io File]))

(def ^:private fixture-dir
  (File. "dev-resources/skeptic/cljs_fixtures/p7"))

(def ^:private foo-file (File. fixture-dir "foo.clj"))
(def ^:private bar-file (File. fixture-dir "bar.cljs"))
(def ^:private baz-file (File. fixture-dir "baz.cljc"))

(def ^:private foo-ns 'skeptic.cljs-fixtures.p7.foo)
(def ^:private bar-ns 'skeptic.cljs-fixtures.p7.bar)
(def ^:private baz-ns 'skeptic.cljs-fixtures.p7.baz)

(def ^:private p5-file (File. "dev-resources/cljs-fixtures/p5.cljs"))

(def ^:private fixture-nss
  {foo-ns foo-file
   bar-ns bar-file
   baz-ns baz-file})

(defn- input-finding
  [results]
  (first (filter #(= :input (:report-kind %)) results)))

(defn- read-exceptions
  [results]
  (filter #(and (= :exception (:report-kind %))
                (= :read (:phase %)))
          results))

(deftest mixed-language-project-attributes-lang-correctly
  (let [ps (pipeline/project-state {} fixture-nss)
        form-opts {:remove-context true}]
    (testing "foo.clj produces a finding tagged :clj"
      (let [{:keys [results]} (pipeline/check-namespace ps foo-ns foo-file form-opts)
            f (input-finding results)]
        (is (some? f) "foo.clj should produce an input mismatch")
        (is (= :clj (get-in f [:location :lang])))))
    (testing "bar.cljs produces a finding tagged :cljs"
      (let [{:keys [results]} (pipeline/check-namespace ps bar-ns bar-file form-opts)
            f (input-finding results)]
        (is (empty? (read-exceptions results)))
        (is (some? f) "bar.cljs should produce an input mismatch")
        (is (= :cljs (get-in f [:location :lang])))))
    (testing "baz.cljc produces one finding tagged #{:clj :cljs}"
      (let [{:keys [results]} (pipeline/check-namespace ps baz-ns baz-file form-opts)
            inputs (filter #(= :input (:report-kind %)) results)]
        (is (empty? (read-exceptions results)))
        (is (= 1 (count inputs))
            "baz.cljc identical findings under both passes should dedup to one")
        (is (= #{:clj :cljs} (get-in (first inputs) [:location :lang])))))))

(deftest cljs-malli-registration-is-admitted-through-production-path
  (let [ps (pipeline/project-state {} {'p5 p5-file})
        {:keys [results provenance]} (pipeline/check-namespace ps 'p5 p5-file {:remove-context true})]
    (is (empty? (read-exceptions results)))
    (is (contains? provenance 'p5/g))
    (is (contains? provenance 'p5/h))))

(def ^:private p6-tests-file
  (File. "dev-resources/cljs-fixtures/p6-cross-require/src/p6/tests.cljs"))
(def ^:private p6-core-file
  (File. "dev-resources/cljs-fixtures/p6-cross-require/src/p6/core.cljs"))

(deftest cross-required-cljs-files-share-analyzer-state
  ;; Regression: cljs.test/run-tests at top level macroexpands to
  ;; cljs.test/test-ns-block, whose assertion (ana-api/find-ns 'p6.tests)
  ;; previously failed because each cljs file used an isolated empty-state.
  ;; The shared cljs compiler state plus dependency-ordered analysis
  ;; populates [::namespaces 'p6.tests] before p6.core is analyzed.
  (let [ps (pipeline/project-state {} {'p6.tests p6-tests-file
                                       'p6.core  p6-core-file})]
    (is (contains? (:cljs-state ps) p6-tests-file))
    (is (contains? (:cljs-state ps) p6-core-file))))

(def ^:private p8-unresolvable-file
  (File. "dev-resources/skeptic/cljs_fixtures/p8_crash_robustness/unresolvable_form.cljc"))
(def ^:private p8-unresolvable-ns 'skeptic.cljs-fixtures.p8-crash-robustness.unresolvable-form)

(def ^:private p8-bad-top-form-file
  (File. "dev-resources/cljs-fixtures/p8-crash-robustness/bad_top_form.cljs"))
(def ^:private p8-bad-top-form-ns 'p8-crash.bad-top-form)

(defn- cljs-expression-exceptions
  [results]
  (filter #(and (= :exception (:report-kind %))
                (= :expression (:phase %))
                (= :cljs (get-in % [:location :lang])))
          results))

(deftest per-form-cljs-crash-isolated
  ;; Regression for the EDN-captured crash in ~/Code/clojurescript: the
  ;; cljs analyzer raises :cljs/analysis-error mid-file for forms it
  ;; cannot parse (e.g. parse-def rejecting an arity-0 (def)). Phase 1's
  ;; per-form try/catch in analyze-source-file converts that throw to an
  ;; entry with :exception, and check-cached-cljs-entry emits an
  ;; :expression-phase exception finding rather than aborting the run.
  ;; Fixture is .cljc with a reader-conditional :cljs branch so the clj
  ;; require during preload-namespaces stays clean.
  (let [ps (pipeline/project-state {} {p8-unresolvable-ns p8-unresolvable-file})
        {:keys [results]} (pipeline/check-namespace ps p8-unresolvable-ns p8-unresolvable-file
                                                    {:remove-context true})]
    (is (seq (cljs-expression-exceptions results))
        "cljs analyzer per-form crash should produce an :expression :exception :lang :cljs finding")))

(deftest pure-cljs-top-form-crash-isolated
  ;; Mirror of per-form-cljs-crash-isolated but for a pure .cljs source
  ;; (no clj branch, no reader conditional). The form `(if)` is rejected
  ;; by the cljs analyzer's parse-if; Phase 1 isolates the throw and the
  ;; expression-phase exception finding is emitted with :lang :cljs.
  (let [ps (pipeline/project-state {} {p8-bad-top-form-ns p8-bad-top-form-file})
        {:keys [results]} (pipeline/check-namespace ps p8-bad-top-form-ns p8-bad-top-form-file
                                                    {:remove-context true})]
    (is (seq (cljs-expression-exceptions results))
        "pure .cljs analyzer crash should produce an :expression :exception :lang :cljs finding")))
