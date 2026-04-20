(ns skeptic.checking.pipeline.malli-or-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest or-output-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/or-output-success)))))

(deftest or-output-bad-fails
  (is (ps/single-failure?
       'skeptic.test-examples.malli-contracts/or-output-bad
       :not-a-string-or-int)))
