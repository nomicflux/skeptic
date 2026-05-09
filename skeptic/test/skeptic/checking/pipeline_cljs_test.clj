(ns skeptic.checking.pipeline-cljs-test
  "Phase 7 smoke gate: a mixed .clj / .cljs / .cljc project produces findings
  whose `:lang` attribution matches the source language. .cljc files run
  both passes and dedup identical findings to `:lang #{:clj :cljs}`."
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.checking.pipeline :as pipeline])
  (:import [java.io File]))

(def ^:private fixture-dir
  (File. "dev-resources/skeptic/cljs_fixtures/p7"))

(def ^:private foo-file (File. fixture-dir "foo.clj"))
(def ^:private bar-file (File. fixture-dir "bar.cljs"))
(def ^:private baz-file (File. fixture-dir "baz.cljc"))

(def ^:private foo-ns 'skeptic.cljs-fixtures.p7.foo)
(def ^:private bar-ns 'skeptic.cljs-fixtures.p7.bar)
(def ^:private baz-ns 'skeptic.cljs-fixtures.p7.baz)

(def ^:private fixture-nss
  [[foo-ns foo-file]
   [bar-ns bar-file]
   [baz-ns baz-file]])

(defn- input-finding
  [results]
  (first (filter #(= :input (:report-kind %)) results)))

(deftest mixed-language-project-attributes-lang-correctly
  (let [ps (pipeline/project-state {} fixture-nss)
        opts {:project-state ps :remove-context true}]
    (testing "foo.clj produces a finding tagged :clj"
      (let [{:keys [results]} (pipeline/check-namespace opts foo-ns foo-file)
            f (input-finding results)]
        (is (some? f) "foo.clj should produce an input mismatch")
        (is (= :clj (get-in f [:location :lang])))))
    (testing "bar.cljs produces a finding tagged :cljs"
      (let [{:keys [results]} (pipeline/check-namespace opts bar-ns bar-file)
            f (input-finding results)]
        (is (some? f) "bar.cljs should produce an input mismatch")
        (is (= :cljs (get-in f [:location :lang])))))
    (testing "baz.cljc produces one finding tagged #{:clj :cljs}"
      (let [{:keys [results]} (pipeline/check-namespace opts baz-ns baz-file)
            inputs (filter #(= :input (:report-kind %)) results)]
        (is (= 1 (count inputs))
            "baz.cljc identical findings under both passes should dedup to one")
        (is (= #{:clj :cljs} (get-in (first inputs) [:location :lang])))))))
