(ns skeptic.checking.pipeline.malli-ref-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest ref-int-output-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/ref-int-output-success)))))

(deftest ref-int-output-bad-value-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/ref-int-output-bad-value)))))

(deftest ref-map-output-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/ref-map-output-success)))))

(deftest ref-map-output-missing-key-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/ref-map-output-missing-key)))))

(deftest recursive-ref-output-nil-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/recursive-ref-output-nil)))))
