(ns skeptic.checking.pipeline.malli-sequential-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(clojure.test/use-fixtures :once ps/with-worker)
(deftest seq-input-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/seq-input-success)))))

(deftest seq-input-bad-element-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/seq-input-bad-element)))))

(deftest seq-output-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/seq-output-success)))))

(deftest seq-output-bad-element-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/seq-output-bad-element)))))
