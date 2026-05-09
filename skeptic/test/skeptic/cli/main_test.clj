(ns skeptic.cli.main-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.cli.main :as main]))

(deftest help-flag-returns-zero-and-prints-summary
  (let [out (with-out-str
              (let [code (main/run-cli ["--help"])]
                (is (zero? code))))]
    (is (re-find #"--verbose" out)
        "the help summary should mention at least one core flag")))

(deftest unrecognized-flag-returns-one
  (let [out (with-out-str
              (let [code (main/run-cli ["--bogus-flag-xyz"])]
                (is (= 1 code))))]
    (is (re-find #"Unknown option|--bogus-flag-xyz" out)
        "errors should be printed when parsing fails")))

(deftest deps-cli-options-present
  (testing "the deps.edn-side options are exposed as a vector for reuse"
    (is (vector? main/deps-cli-options))
    (is (some #(= "--paths PATHS" (nth % 1 nil)) main/deps-cli-options))
    (is (some #(= "--alias ALIAS" (nth % 1 nil)) main/deps-cli-options))))
