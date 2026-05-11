(ns skeptic.intake.distinctness-test
  "Cross-stream regression: asserts hermetic separation between Plumatic and
  Malli intake. Each Var appears in the expected stream; cross-stream Var
  (declared via BOTH s/defn AND m/=>) appears in BOTH; mx/defn never leaks
  into the Plumatic stream despite writing :schema Var-meta."
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.malli-spec.collect :as malli-collect]
            [skeptic.schema.collect :as schema-collect])
  (:import [java.io File]))

(def fixture-ns 'skeptic.research.intake-combined-fixture)
(def fixture-file (File. "test/skeptic/research/intake_combined_fixture.clj"))

(defn- qsym [name-str] (symbol "skeptic.research.intake-combined-fixture" name-str))

(deftest plumatic-stream-admits-only-plumatic-source-forms
  (require fixture-ns)
  (let [{:keys [entries]} (schema-collect/ns-schema-results
                           {} fixture-ns fixture-file)]
    (testing "Plumatic source-form heads are admitted (alias-resolved)"
      (is (contains? entries (qsym "aliased-defn")))
      (is (contains? entries (qsym "qualified-defn")))
      (is (contains? entries (qsym "schemy-defn")))
      (is (contains? entries (qsym "aliased-def"))))
    (testing "cross-stream Var admitted via its s/defn declaration"
      (is (contains? entries (qsym "cross-stream"))))
    (testing "non-Plumatic Vars do NOT enter the Plumatic stream"
      (is (not (contains? entries (qsym "plain-defn"))))
      (is (not (contains? entries (qsym "malli-arrow"))))
      (is (not (contains? entries (qsym "malli-meta-only")))))
    (testing "mx/defn does NOT leak into Plumatic stream (writes :schema Var-meta but the stream no longer reads it)"
      (is (not (contains? entries (qsym "malli-mx")))))))

(deftest malli-stream-admits-only-malli-sources
  (require fixture-ns)
  (let [{:keys [entries]} (malli-collect/ns-malli-spec-results {} fixture-ns)]
    (testing "compile-time-registered m/=> admitted"
      (is (contains? entries (qsym "malli-arrow"))))
    (testing "compile-time-registered mx/defn admitted"
      (is (contains? entries (qsym "malli-mx"))))
    (testing ":malli/schema Var-meta admitted"
      (is (contains? entries (qsym "malli-meta-only"))))
    (testing "cross-stream Var admitted via its m/=> declaration"
      (is (contains? entries (qsym "cross-stream"))))
    (testing "non-Malli Vars do NOT enter the Malli stream"
      (is (not (contains? entries (qsym "plain-defn"))))
      (is (not (contains? entries (qsym "aliased-defn"))))
      (is (not (contains? entries (qsym "aliased-def"))))
      (is (not (contains? entries (qsym "AliasedSchema")))))))

(deftest cross-stream-var-appears-in-both-streams
  (require fixture-ns)
  (let [plumatic-entries (:entries (schema-collect/ns-schema-results
                                    {} fixture-ns fixture-file))
        malli-entries (:entries (malli-collect/ns-malli-spec-results
                                 {} fixture-ns))
        cross-sym (qsym "cross-stream")]
    (is (contains? plumatic-entries cross-sym)
        "cross-stream Var (s/defn + m/=>) admitted by Plumatic stream")
    (is (contains? malli-entries cross-sym)
        "cross-stream Var (s/defn + m/=>) admitted by Malli stream")))
