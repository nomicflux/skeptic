(ns skeptic.checking.pipeline.malli-contracts-integration-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest combined-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/combined-success)))))

(deftest combined-bad-fails
  (is (ps/single-failure?
       'skeptic.test-examples.malli-contracts/combined-bad
       :bad-keyword)))
