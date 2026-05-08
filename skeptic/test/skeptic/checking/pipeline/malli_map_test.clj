(ns skeptic.checking.pipeline.malli-map-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest map-output-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/map-output-success)))))

(deftest map-output-bad-value-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/map-output-bad-value)))))

(deftest map-output-missing-key-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/map-output-missing-key)))))

(deftest map-output-optional-key-omitted-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/map-output-optional-key-omitted)))))

(deftest map-output-optional-key-present-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/map-output-optional-key-present)))))
