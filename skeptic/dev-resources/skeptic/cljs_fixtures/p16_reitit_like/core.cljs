(ns skeptic.cljs-fixtures.p16-reitit-like.core
  (:require [cljs.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skeptic.cljs-fixtures.p16-reitit-like.dep-test :as dep-test]))

(defn instrument-all [f]
  (try
    (stest/instrument)
    (f)
    (finally
      (stest/unstrument))))

(use-fixtures :each instrument-all)

(deftest route-data-validation-test
  (testing "handler can be a var"
    (is (thrown-with-msg?
         ExceptionInfo
         #"boom"
         (throw (ex-info "boom" {}))))
    (is (dep-test/accept-var #'identity))))
