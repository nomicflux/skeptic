(ns skeptic.cli.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skeptic.cli.main :as main]))

(deftest legacy-m-entrypoint-is-rejected
  (let [err (with-out-str
              (binding [*err* *out*]
                (is (= 1 (main/run-cli ["--help"])))))]
    (is (str/includes? err "clojure -M:skeptic is unsupported"))
    (is (str/includes? err "clj -T:skeptic check"))))

(deftest legacy-m-entrypoint-does-not-parse-project-options
  (let [err (with-out-str
              (binding [*err* *out*]
                (is (= 1 (main/run-cli ["--bogus-flag-xyz"])))))]
    (is (not (str/includes? err "Unknown option")))
    (is (str/includes? err "deps.edn tool alias"))))

(deftest deps-cli-options-present
  (testing "the deps.edn-side options are exposed as a vector for reuse"
    (is (vector? main/deps-cli-options))
    (is (some #(= "--paths PATHS" (nth % 1 nil)) main/deps-cli-options))
    (is (some #(= "--alias ALIAS" (nth % 1 nil)) main/deps-cli-options))))
