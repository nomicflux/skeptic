(ns skeptic.checking.pipeline.malli-and-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(clojure.test/use-fixtures :once ps/with-worker)
(deftest and-output-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/and-output-success)))))

(deftest and-output-bad-fails
  (is (ps/single-failure?
       'skeptic.test-examples.malli-contracts/and-output-bad
       :not-an-int)))
