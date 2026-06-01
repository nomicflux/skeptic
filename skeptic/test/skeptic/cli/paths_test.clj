(ns skeptic.cli.paths-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skeptic.cli.paths :as paths])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-cli-paths-"
                                      (into-array FileAttribute []))))

(defn- write-deps!
  [dir contents]
  (let [f (io/file dir "deps.edn")]
    (spit f (pr-str contents))
    f))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(deftest discover-paths-reads-paths-key
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src" "test"]})
      (let [discovered (paths/discover-paths (.getAbsolutePath dir) [])]
        (is (some #{"src"} discovered))
        (is (some #{"test"} discovered)))
      (finally (delete-recursively! dir)))))

(deftest discover-paths-merges-alias-extra-paths
  (let [dir (temp-dir!)]
    (try
      (write-deps! dir {:paths ["src"]
                        :aliases {:dev {:extra-paths ["dev"]}}})
      (testing "without alias selection, :extra-paths are not merged"
        (is (not (some #{"dev"} (paths/discover-paths (.getAbsolutePath dir) [])))))
      (testing "with alias selection, :extra-paths are merged"
        (let [discovered (paths/discover-paths (.getAbsolutePath dir) [:dev])]
          (is (some #{"src"} discovered))
          (is (some #{"dev"} discovered))))
      (finally (delete-recursively! dir)))))

(deftest discover-paths-throws-when-deps-edn-missing
  (let [dir (temp-dir!)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No deps\.edn"
                            (paths/discover-paths (.getAbsolutePath dir) [])))
      (finally (delete-recursively! dir)))))
