(ns skeptic.intake.distinctness-test
  "Cross-stream regression: asserts hermetic separation between Plumatic and
  Malli intake. Each Var appears in the expected stream; cross-stream Var
  (declared via BOTH s/defn AND m/=>) appears in BOTH; mx/defn never leaks
  into the Plumatic stream despite writing :schema Var-meta.

  Hermetic: both collectors are fed inert worker-shipped data (top-level
  source-forms + the captured :malli-schema field), never a live namespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [skeptic.malli-spec.collect :as malli-collect]
            [skeptic.schema.collect.clj-source :as clj-source]
            [skeptic.schema.discovery :as discovery]
            [skeptic.test-support.admit :as admit]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def fixture-ns 'skeptic.research.intake-combined-fixture)
(def fixture-file "test/skeptic/research/intake_combined_fixture.clj")

(defn- qsym [name-str] (symbol "skeptic.research.intake-combined-fixture" name-str))

(defn- worker-entries []
  (admit/entries fixture-ns fixture-file))

(defn- plumatic-entries []
  (:entries (clj-source/ns-schema-results-clj fixture-ns (worker-entries))))

(defn- malli-entries []
  (let [entries (worker-entries)
        forms (mapv :source-form entries)
        aliases (discovery/source-form-aliases forms)]
    (:entries (malli-collect/ns-malli-spec-results fixture-ns aliases entries))))

(deftest plumatic-stream-admits-only-plumatic-source-forms
  (let [entries (plumatic-entries)]
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
    (testing "mx/defn does NOT leak into Plumatic stream"
      (is (not (contains? entries (qsym "malli-mx")))))))

(deftest malli-stream-admits-only-malli-sources
  (let [entries (malli-entries)]
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
  (let [cross-sym (qsym "cross-stream")]
    (is (contains? (plumatic-entries) cross-sym)
        "cross-stream Var (s/defn + m/=>) admitted by Plumatic stream")
    (is (contains? (malli-entries) cross-sym)
        "cross-stream Var (s/defn + m/=>) admitted by Malli stream")))
