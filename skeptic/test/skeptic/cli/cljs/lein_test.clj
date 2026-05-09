(ns skeptic.cli.cljs.lein-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skeptic.cli.cljs.lein :as sut])
  (:import [java.io File]))

(def fixture-root "dev-resources/cljs-fixtures/p2-lein")

(defn- abs-path ^String [path]
  (.getAbsolutePath (io/file path)))

(defn- file-names [files]
  (set (map (fn [^File f] (.getName f)) files)))

(defn- project-map []
  {:root (abs-path fixture-root)
   :source-paths [(abs-path (str fixture-root "/src"))]
   :test-paths [(abs-path (str fixture-root "/test"))]
   :cljsbuild {:builds [{:source-paths ["src-cljsbuild"]}]}})

(deftest discover-sources-spans-source-test-and-cljsbuild-paths
  (let [{:keys [source-paths cljs-files cljc-files]}
        (sut/discover-sources (project-map))]
    (testing "source-paths union covers src, test, and cljsbuild"
      (is (= 3 (count source-paths))))
    (testing "cljs-files = src/foo.cljs ∪ src-cljsbuild/extra.cljs"
      (is (= #{"foo.cljs" "extra.cljs"} (file-names cljs-files))))
    (testing "cljc-files = src/bar.cljc ∪ test/extra_test.cljc"
      (is (= #{"bar.cljc" "extra_test.cljc"} (file-names cljc-files))))))

(deftest discover-sources-with-no-cljsbuild-still-works
  (let [project (dissoc (project-map) :cljsbuild)
        {:keys [cljs-files cljc-files]} (sut/discover-sources project)]
    (testing "cljs-files reduces to just src/foo.cljs"
      (is (= #{"foo.cljs"} (file-names cljs-files))))
    (testing "cljc-files unchanged"
      (is (= #{"bar.cljc" "extra_test.cljc"} (file-names cljc-files))))))
