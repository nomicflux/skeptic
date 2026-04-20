(ns skeptic.checking.pipeline.malli-maybe-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest maybe-input-accepts-nil
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/maybe-caller-success)))))

(deftest maybe-output-mismatch-fails
  (is (ps/single-failure?
       'skeptic.test-examples.malli-contracts/maybe-output-bad
       "not-an-int")))
