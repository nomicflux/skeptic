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

(deftest narrowing-conflict-flags-ground-mismatch
  (testing "successful Keyword-typed call narrows arg, exposing (+ x 1) as conflict"
    (let [results (ps/check-fixture
                   'skeptic.test-examples.predicate-examples/narrow-via-call-keyword-failure)]
      (is (seq results)
          "expected at least one result for narrow-via-call-keyword-failure")
      (is (some (comp seq :errors) results)
          "expected (+ x 1) to be flagged after keyword-typed call narrows x")))
  (testing "let-bound predicate result carries truthy refinement into (when y ...)"
    (let [results (ps/check-fixture
                   'skeptic.test-examples.predicate-examples/narrow-via-let-bound-pred-keyword-failure)]
      (is (seq results)
          "expected at least one result for narrow-via-let-bound-pred-keyword-failure")
      (is (some (comp seq :errors) results)
          "expected (+ x 1) to be flagged after (when y) narrows x to Keyword"))))
