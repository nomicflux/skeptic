(ns skeptic.checking.pipeline.predicate-pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.checking.pipeline.support :as ps]))

(defn- no-errors?
  [results]
  (every? (fn [r]
            (and (not= :exception (:report-kind r))
                 (empty? (:errors r))))
          results))

(deftest schema-pred-success-fixtures
  (testing "(s/pred string?) input"
    (is (no-errors? (ps/check-fixture
                     'skeptic.test-examples.predicate-examples/schema-pred-string-input-success
                     {:keep-empty true}))))
  (testing "(s/pred pos?) input"
    (is (no-errors? (ps/check-fixture
                     'skeptic.test-examples.predicate-examples/schema-pred-pos-input-success
                     {:keep-empty true}))))
  (testing "(s/pred nil?) input"
    (is (no-errors? (ps/check-fixture
                     'skeptic.test-examples.predicate-examples/schema-pred-nil-input-success
                     {:keep-empty true})))))

(deftest malli-bare-predicate-success-fixtures
  (testing "Malli bare string? input"
    (is (no-errors? (ps/check-fixture
                     'skeptic.test-examples.predicate-examples/malli-string-pred-input-success
                     {:keep-empty true}))))
  (testing "Malli bare int? input"
    (is (no-errors? (ps/check-fixture
                     'skeptic.test-examples.predicate-examples/malli-int-pred-input-success
                     {:keep-empty true})))))
