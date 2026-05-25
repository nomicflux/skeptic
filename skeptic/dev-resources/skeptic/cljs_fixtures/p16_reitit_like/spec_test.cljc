(ns skeptic.cljs-fixtures.p16-reitit-like.spec-test
  (:require [#?(:clj clojure.spec.test.alpha :cljs cljs.spec.test.alpha) :as stest]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]])
  #?(:clj
     (:import (clojure.lang ExceptionInfo))))

(defn instrument-all [f]
  (try
    (stest/instrument)
    (f)
    (finally
      (stest/unstrument))))

(use-fixtures :each instrument-all)

(deftest route-data-validation-test
  (testing "validation"
    (is (s/valid? any? 1))
    (is (thrown-with-msg?
         ExceptionInfo
         #"boom"
         (throw (ex-info "boom" {}))))))
