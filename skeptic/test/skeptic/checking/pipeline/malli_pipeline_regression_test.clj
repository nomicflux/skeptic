(ns skeptic.checking.pipeline.malli-pipeline-regression-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest malli-map-schema-falls-back-to-dyn-no-exception
  (is (every? #(not= :exception (:report-kind %))
              (ps/check-fixture 'skeptic.test-examples.malli-contracts/map-dyn-caller
                                {:keep-empty true}))))
