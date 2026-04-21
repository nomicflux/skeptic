(ns skeptic.analysis.native-fns-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.native-fns :as sut]
            [skeptic.analysis.types :as at]))

(deftest native-fn-dict-fn-method-names-match-inputs
  (let [assertion-count (atom 0)]
    (doseq [[_ fun-t] sut/native-fn-dict
            method (at/fun-methods fun-t)]
      (is (= (count (:names method)) (count (:inputs method))))
      (swap! assertion-count inc))
    (is (pos? @assertion-count))))
