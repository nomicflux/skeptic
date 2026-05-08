(ns skeptic.checking.pipeline.malli-tuple-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest tuple-output-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/tuple-output-success)))))

(deftest tuple-output-bad-element-fails
  (is (ps/single-failure?
       'skeptic.test-examples.malli-contracts/tuple-output-bad-element
       '[x :not-a-string])))

(deftest tuple-output-bad-arity-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/tuple-output-bad-arity)))))
