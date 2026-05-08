(ns skeptic.checking.pipeline.malli-multi-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest multi-output-success-a-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/multi-output-success-a)))))

(deftest multi-output-success-b-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/multi-output-success-b)))))

(deftest multi-output-bad-value-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/multi-output-bad-value)))))

(deftest multi-output-default-branch-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/multi-output-default-branch)))))
