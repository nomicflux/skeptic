(ns skeptic.malli-spec.collect-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.malli-spec.collect :as sut]))

(deftest ns-malli-spec-results-stub-shape
  (is (= {:entries {} :errors []}
         (sut/ns-malli-spec-results {} 'skeptic.malli-spec.collect-test))))

(deftest ns-malli-specs-stub-shape
  (is (= {} (sut/ns-malli-specs {} 'skeptic.malli-spec.collect-test))))
