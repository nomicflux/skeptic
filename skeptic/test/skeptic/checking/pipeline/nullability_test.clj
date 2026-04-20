(ns skeptic.checking.pipeline.nullability-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest guarded-keys-nullability-contract
  (let [results (ps/check-fixture 'skeptic.test-examples.nullability/guarded-keys-caller)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest when-not-blank-maybe-str
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-not-blank-maybe-str-success))))

(deftest presents-str
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/presents-str))))

(deftest when-not-throw-nil-local
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-not-throw-nil-local-success))))

(deftest when-truthy-nil-local
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-truthy-nil-local-success))))

(deftest when-and-some?-nil
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-and-some?-nil-success))))

(deftest when-and-some?-and-nil
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-and-some?-and-nil-success))))

(deftest when-and-some?-multi-nil
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-and-some?-multi-nil-success))))
