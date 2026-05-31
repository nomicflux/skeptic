(ns skeptic.schema.collect.clj-source-test
  "Tests for JVM Plumatic admission from worker-captured schema values."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.schema.collect.clj-source :as sut]
            [skeptic.test-support.admit :as admit]
            [skeptic.test-support.shared-worker :as shared-worker])
  (:import [java.io File]))

(use-fixtures :once shared-worker/with-shared-worker)

(defn- collect-file
  [ns-sym source-file]
  (let [{:keys [entries]} (admit/plumatic-args ns-sym source-file)]
    (sut/ns-schema-results-clj ns-sym entries)))

(deftest admits-s-defn-from-worker-schema-value
  (let [{:keys [entries errors]} (collect-file
                                  'skeptic.test-examples.basics
                                  (File. "test/skeptic/test_examples/basics.clj"))
        desc (get entries 'skeptic.test-examples.basics/int-add)]
    (is (= [] errors))
    (testing "output schema comes from evaluated worker Var metadata"
      (is (= s/Int (:output desc))))
    (testing "input schemas and arglists are preserved"
      (is (= 3 (count (:arglists desc))))
      (is (= s/Int (get-in desc [:arglists 2 :schema 0 :schema]))))))

(deftest admits-defschema-and-recursive-project-refs-from-worker-schema-value
  (let [source-file (File. "test/skeptic/test_examples/named_fold_contract_probe.clj")
        worker-entries (admit/entries 'skeptic.test-examples.named-fold-contract-probe source-file)
        {:keys [entries errors]} (sut/ns-schema-results-clj
                                  'skeptic.test-examples.named-fold-contract-probe
                                  worker-entries)
        var-provs (set (keep :plumatic-var-prov worker-entries))]
    (is (= [] errors))
    (is (contains? var-provs 'skeptic.test-examples.named-fold-contract-probe/Threal))
    (is (contains? var-provs 'skeptic.test-examples.named-fold-contract-probe/ThrealCache))
    (is (contains? var-provs 'skeptic.test-examples.named-fold-contract-probe/ThrealPairCache))
    (is (contains? entries 'skeptic.test-examples.named-fold-contract-probe/cache-hit-analogue))))

(deftest admits-combinators-from-worker-schema-value
  (let [{:keys [entries errors]} (collect-file
                                  'skeptic.test-examples.named-fold-contract-probe
                                  (File. "test/skeptic/test_examples/named_fold_contract_probe.clj"))]
    (is (= [] errors))
    (doseq [sym ['produce-conditional 'produce-either 'produce-cond-pre
                 'produce-both 'produce-maybe 'produce-constrained]]
      (is (contains? entries
                     (symbol "skeptic.test-examples.named-fold-contract-probe" (name sym)))))))

(deftest preserves-ignore-body-worker-flags
  (let [{:keys [entries errors]} (collect-file
                                  'skeptic.test-examples.fixture-flags
                                  (File. "test/skeptic/test_examples/fixture_flags.clj"))]
    (is (= [] errors))
    (is (true? (:skeptic/ignore-body? (get entries 'skeptic.test-examples.fixture-flags/ignored-body-fn))))
    (is (not (contains? entries 'skeptic.test-examples.fixture-flags/opaque-fn)))))
