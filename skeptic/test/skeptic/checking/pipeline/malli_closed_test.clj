(ns skeptic.checking.pipeline.malli-closed-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(clojure.test/use-fixtures :once ps/with-worker)
(deftest closed-map-output-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/closed-map-output-success)))))

(deftest closed-map-extra-key-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/closed-map-extra-key-fails)))))

(deftest open-map-extra-key-allowed-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/open-map-extra-key-allowed)))))

(deftest open-map-required-key-missing-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/open-map-required-key-missing)))))
