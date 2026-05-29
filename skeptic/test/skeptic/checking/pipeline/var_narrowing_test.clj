(ns skeptic.checking.pipeline.var-narrowing-test
  (:require [clojure.test :refer [are deftest]]
            [skeptic.checking.pipeline.support :as ps]))

(clojure.test/use-fixtures :once ps/with-worker)
(deftest var-narrowing-via-some-pred
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.var-narrowing/server-host-when-present-success
    'skeptic.test-examples.var-narrowing/report-kind-cased))
