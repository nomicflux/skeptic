(ns skeptic.cli.options-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.cli.options :as opts]))

(defn- parsed
  ([args] (parsed args nil))
  ([args extra] (:options (opts/parse args extra))))

(deftest core-flags-map-to-expected-keys
  (testing "boolean short flags"
    (is (true? (:verbose       (parsed ["-v"]))))
    (is (true? (:analyzer      (parsed ["-a"]))))
    (is (true? (:keep-empty    (parsed ["-k"]))))
    (is (true? (:show-context  (parsed ["-c"]))))
    (is (true? (:porcelain     (parsed ["-p"])))))
  (testing "long-only boolean flags"
    (is (true? (:explain-full     (parsed ["--explain-full"]))))
    (is (true? (:plumatic-disable (parsed ["--plumatic-disable"]))))
    (is (true? (:malli-disable    (parsed ["--malli-disable"]))))
    (is (true? (:debug            (parsed ["--debug"]))))
    (is (true? (:profile          (parsed ["--profile"])))))
  (testing "value flags"
    (is (= "out.jsonl" (:output (parsed ["-o" "out.jsonl"])))))
  (testing "help flag"
    (is (true? (:help (parsed ["-h"]))))))

(deftest namespace-flag-is-repeatable-and-multi
  (is (= ["a" "b"] (:namespace (parsed ["-n" "a" "-n" "b"]))))
  (is (= ["a,b"]   (:namespace (parsed ["-n" "a,b"])))
      "comma-splitting is downstream in skeptic.core; parse keeps the raw string"))

(deftest absent-flags-omit-keys
  (let [m (parsed [])]
    (is (not (contains? m :verbose)))
    (is (not (contains? m :namespace)))
    (is (not (contains? m :output)))))

(deftest extra-options-merge-into-parser
  (testing "passing deps.edn-side options through opts/parse"
    (let [extra [[nil "--paths PATHS" "comma-separated"]
                 [nil "--alias ALIAS" :multi true :update-fn (fnil conj [])
                  :parse-fn keyword]]]
      (is (= "src,test" (:paths (parsed ["--paths" "src,test"] extra))))
      (is (= [:dev :test] (:alias (parsed ["--alias" "dev" "--alias" "test"] extra)))))))

(deftest unrecognized-flag-produces-errors
  (let [{:keys [errors options]} (opts/parse ["--bogus"])]
    (is (seq errors))
    (is (not (:bogus options)))))
