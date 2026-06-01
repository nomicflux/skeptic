(ns skeptic.checking.pipeline-cljs-test
  "Phase 7 smoke gate: a mixed .clj / .cljs / .cljc project produces findings
  whose `:lang` attribution matches the source language. .cljc files run
  both passes and dedup identical findings to `:lang #{:clj :cljs}`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skeptic.analysis.class-oracle :as class-oracle]
            [skeptic.analysis.annotate.fn :as fn-annotate]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.checking.pipeline.support :as psup]
            [skeptic.worker.client :as wc]
            [skeptic.worker.process :as proc])
  (:import [java.io File]))

;; Bind a real worker for the whole namespace: project-state's cljs preload
;; issues the analyze-cljs-namespace op over class-oracle/*worker-conn*, so
;; without a live worker cljs-state stays empty and every fixture reports
;; "cljs admission failed". Route project-state through ps/project-state-for-nses
;; so :worker-conn rides along, exactly as the clj pipeline tests do.
(use-fixtures :once psup/with-worker)

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
  (let [ps (psup/project-state-for-nses fixture-nss)
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
  (let [ps (psup/project-state-for-nses {'p5 p5-file})
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
  (let [ps (psup/project-state-for-nses {'p6.tests p6-tests-file
                                       'p6.core  p6-core-file})]
    (is (contains? (:cljs-state ps) p6-tests-file))
    (is (contains? (:cljs-state ps) p6-core-file))))

(def ^:private p8-unresolvable-file
  (File. "dev-resources/skeptic/cljs_fixtures/p8_crash_robustness/unresolvable_form.cljc"))
(def ^:private p8-unresolvable-ns 'skeptic.cljs-fixtures.p8-crash-robustness.unresolvable-form)

(def ^:private p8-bad-top-form-file
  (File. "dev-resources/cljs-fixtures/p8-crash-robustness/bad_top_form.cljs"))
(def ^:private p8-bad-top-form-ns 'p8-crash.bad-top-form)

(def ^:private p9-recursive-specialization-file
  (File. "dev-resources/skeptic/cljs_fixtures/p9_recursive_specialization/core.cljs"))
(def ^:private p9-recursive-specialization-ns
  'skeptic.cljs-fixtures.p9-recursive-specialization.core)

(def ^:private p10-var-quote-dep-file
  (File. "dev-resources/skeptic/cljs_fixtures/p10_var_quote/dep.cljs"))
(def ^:private p10-var-quote-core-file
  (File. "dev-resources/skeptic/cljs_fixtures/p10_var_quote/core.cljs"))
(def ^:private p10-var-quote-dep-ns
  'skeptic.cljs-fixtures.p10-var-quote.dep)
(def ^:private p10-var-quote-core-ns
  'skeptic.cljs-fixtures.p10-var-quote.core)

(def ^:private p13-macro-publics-file
  (File. "dev-resources/skeptic/cljs_fixtures/p13_macro_publics/core.cljs"))
(def ^:private p13-macro-publics-ns
  'skeptic.cljs-fixtures.p13-macro-publics.core)

(def ^:private p14-malli-mx-file
  (File. "dev-resources/cljs-fixtures/p14_malli_mx.cljs"))
(def ^:private p14-malli-mx-ns 'p14-malli-mx)

(def ^:private p15-registry-side-effect-file
  (File. "dev-resources/cljs-fixtures/p15_registry_side_effect.cljc"))
(def ^:private p15-registry-side-effect-ns 'p15-registry-side-effect)
(def ^:private p15-malli-mx-file
  (File. "dev-resources/cljs-fixtures/p15_malli_mx.cljs"))
(def ^:private p15-malli-mx-ns 'p15-malli-mx)

(def ^:private p16-shadow-preload-file
  (File. "dev-resources/cljs-fixtures/p16_shadow_preload.cljc"))
(def ^:private p16-shadow-preload-ns 'p16-shadow-preload)

(defn- with-isolated-worker
  [f]
  (let [worker (proc/spawn! (System/getProperty "java.class.path"))
        conn (wc/connect (:port worker))]
    (try
      (binding [class-oracle/*worker-conn* conn
                class-oracle/*host-class-handles* (class-oracle/intern-host-classes! conn)
                class-oracle/*class-rel-cache* (atom {})
                class-oracle/*predicate-cache* (atom {})]
        (f conn))
      (finally
        (wc/disconnect! conn)
        (proc/stop! worker)))))

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
  (let [ps (psup/project-state-for-nses {p8-unresolvable-ns p8-unresolvable-file})
        {:keys [results]} (pipeline/check-namespace ps p8-unresolvable-ns p8-unresolvable-file
                                                    {:remove-context true})]
    (is (seq (cljs-expression-exceptions results))
        "cljs analyzer per-form crash should produce an :expression :exception :lang :cljs finding")))

(deftest pure-cljs-top-form-crash-isolated
  ;; Mirror of per-form-cljs-crash-isolated but for a pure .cljs source
  ;; (no clj branch, no reader conditional). The form `(if)` is rejected
  ;; by the cljs analyzer's parse-if; Phase 1 isolates the throw and the
  ;; expression-phase exception finding is emitted with :lang :cljs.
  (let [ps (psup/project-state-for-nses {p8-bad-top-form-ns p8-bad-top-form-file})
        {:keys [results]} (pipeline/check-namespace ps p8-bad-top-form-ns p8-bad-top-form-file
                                                    {:remove-context true})]
    (is (seq (cljs-expression-exceptions results))
        "pure .cljs analyzer crash should produce an :expression :exception :lang :cljs finding")))

(deftest cljs-var-quote-analysis-preserves-callee-identity-through-production-path
  (let [ps (psup/project-state-for-nses {p10-var-quote-dep-ns p10-var-quote-dep-file
                                       p10-var-quote-core-ns p10-var-quote-core-file})
        {:keys [results]} (pipeline/check-namespace ps p10-var-quote-core-ns p10-var-quote-core-file
                                                      {:remove-context true})
        input-findings (filter #(= :input (:report-kind %)) results)]
    (is (empty? (read-exceptions results)))
    (is (empty? (cljs-expression-exceptions results)))
    (is (seq input-findings)
        "Calling a schema-typed function through #'alias/name should still report the input mismatch.")))

(deftest macro-requested-analyzer-namespace-loads-through-production-path
  (let [ps (psup/project-state-for-nses {p13-macro-publics-ns p13-macro-publics-file})
        {:keys [results]} (pipeline/check-namespace ps p13-macro-publics-ns p13-macro-publics-file
                                                    {:remove-context true})]
    (is (contains? (:cljs-state ps) p13-macro-publics-file))
    (is (empty? (read-exceptions results)))
    (is (empty? (cljs-expression-exceptions results)))))

(deftest malli-experimental-defn-macroexpands-through-worker-cljs-path
  (let [ps (psup/project-state-for-nses {p14-malli-mx-ns p14-malli-mx-file})
        {:keys [results]} (pipeline/check-namespace ps p14-malli-mx-ns p14-malli-mx-file
                                                    {:remove-context true})]
    (is (contains? (:cljs-state ps) p14-malli-mx-file))
    (is (empty? (read-exceptions results)))
    (is (empty? (cljs-expression-exceptions results)))))

(deftest cljs-preload-precedes-clj-top-level-side-effects
  (with-isolated-worker
    (fn [_conn]
      (let [ps (pipeline/project-state
                {:worker-conn class-oracle/*worker-conn*}
                {p15-registry-side-effect-ns p15-registry-side-effect-file
                 p15-malli-mx-ns p15-malli-mx-file})
            {:keys [results]} (pipeline/check-namespace ps p15-malli-mx-ns p15-malli-mx-file
                                                        {:remove-context true})]
        (is (contains? (:cljs-state ps) p15-malli-mx-file))
        (is (empty? (read-exceptions results)))
        (is (empty? (cljs-expression-exceptions results)))))))

(deftest shadow-preload-cljc-is-treated-as-cljs-only
  (let [opts {:worker-conn class-oracle/*worker-conn*
              :cljs-only-namespaces #{p16-shadow-preload-ns}}
        ps (pipeline/project-state opts {p16-shadow-preload-ns p16-shadow-preload-file})
        {:keys [results]} (pipeline/check-namespace ps p16-shadow-preload-ns p16-shadow-preload-file
                                                    {:remove-context true
                                                     :cljs-only-namespaces #{p16-shadow-preload-ns}})]
    (is (contains? (:cljs-state ps) p16-shadow-preload-file))
    (is (empty? (read-exceptions results)))
    (is (empty? (cljs-expression-exceptions results)))))

(deftest recursive-local-fn-specialization-is-finite-through-cljs-production-path
  (let [step-annotations (atom {})
        original-annotate-fn fn-annotate/annotate-fn
        ps (psup/project-state-for-nses {p9-recursive-specialization-ns p9-recursive-specialization-file})
        result (with-redefs [fn-annotate/annotate-fn
                             (fn [ctx node]
                               (when (= 'step (:name node))
                                 (let [form-str (pr-str (:form node))
                                       step-kind (if (str/includes? form-str "lazy-seq")
                                                   :map-shaped
                                                   :bad-output)]
                                   (swap! step-annotations update step-kind (fnil inc 0))))
                               (original-annotate-fn ctx node))]
                 (pipeline/check-namespace ps p9-recursive-specialization-ns p9-recursive-specialization-file
                                           {:remove-context true}))]
    (is (empty? (read-exceptions (:results result))))
    (is (<= (get @step-annotations :map-shaped 0) 3)
        "the cljs.core/map-shaped recursive local `step` specialization must be shared instead of expanded repeatedly")
    (is (<= (get @step-annotations :bad-output 0) 3)
        "the bad-output recursive local `step` specialization must be shared instead of expanded repeatedly")))

(deftest recursive-local-fn-specialization-preserves-output-checking
  (let [ps (psup/project-state-for-nses {p9-recursive-specialization-ns p9-recursive-specialization-file})
        {:keys [results]} (pipeline/check-namespace ps p9-recursive-specialization-ns p9-recursive-specialization-file
                                                    {:remove-context true})
        output-findings (filter #(= :output (:report-kind %)) results)]
    (is (empty? (read-exceptions results)))
    (is (= 1 (count output-findings)))
    (is (= :source-union (:rule (first output-findings)))
        "a recursive specialization reference must still resolve far enough to reject incompatible declared output")))
