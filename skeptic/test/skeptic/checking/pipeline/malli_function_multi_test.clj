(ns skeptic.checking.pipeline.malli-function-multi-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(clojure.test/use-fixtures :once ps/with-worker)
(deftest multi-arity-success-passes
  (is (empty? (ps/result-errors
               (ps/check-fixture
                'skeptic.test-examples.malli-contracts/multi-arity-success)))))

(deftest multi-arity-bad-arg-fails
  (is (seq (ps/result-errors
            (ps/check-fixture
             'skeptic.test-examples.malli-contracts/multi-arity-bad-arg)))))
