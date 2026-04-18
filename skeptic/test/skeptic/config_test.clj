(ns skeptic.config-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.config :as sut])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- make-temp-dir []
  (.toFile (Files/createTempDirectory "skeptic-config-test"
                                      (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-dir [^File dir]
  (doseq [f (reverse (file-seq dir))]
    (.delete f)))

(deftest load-raw-config-returns-empty-when-missing
  (let [tmp (make-temp-dir)]
    (try
      (is (= {} (sut/load-raw-config tmp)))
      (finally (delete-dir tmp)))))

(deftest load-raw-config-reads-edn-when-present
  (let [tmp (make-temp-dir)
        config-dir (File. tmp ".skeptic")]
    (try
      (.mkdirs config-dir)
      (spit (File. config-dir "config.edn") "{:exclude-files [\"src/foo.clj\"]}")
      (is (= {:exclude-files ["src/foo.clj"]} (sut/load-raw-config tmp)))
      (finally (delete-dir tmp)))))

(deftest path-excluded?-matches-exact-path
  (let [tmp (make-temp-dir)
        f (File. tmp "src/skeptic/examples.clj")]
    (try
      (.mkdirs (.getParentFile f))
      (.createNewFile f)
      (is (sut/path-excluded? tmp ["src/skeptic/examples.clj"] f))
      (finally (delete-dir tmp)))))

(deftest path-excluded?-matches-glob
  (let [tmp (make-temp-dir)
        dir (File. tmp "test/skeptic")]
    (try
      (.mkdirs dir)
      (let [f1 (File. dir "test_examples.clj")
            f2 (File. dir "best_effort_examples.clj")]
        (.createNewFile f1)
        (.createNewFile f2)
        (is (sut/path-excluded? tmp ["test/skeptic/*examples*.clj"] f1))
        (is (sut/path-excluded? tmp ["test/skeptic/*examples*.clj"] f2)))
      (finally (delete-dir tmp)))))

(deftest path-excluded?-returns-false-for-nonmatch
  (let [tmp (make-temp-dir)
        f (File. tmp "src/skeptic/core.clj")]
    (try
      (.mkdirs (.getParentFile f))
      (.createNewFile f)
      (is (not (sut/path-excluded? tmp ["src/skeptic/examples.clj"] f)))
      (finally (delete-dir tmp)))))

(deftest path-excluded?-empty-patterns-returns-false
  (let [tmp (make-temp-dir)
        f (File. tmp "src/skeptic/examples.clj")]
    (try
      (.mkdirs (.getParentFile f))
      (.createNewFile f)
      (is (not (sut/path-excluded? tmp [] f)))
      (is (not (sut/path-excluded? tmp nil f)))
      (finally (delete-dir tmp)))))

(deftest compile-overrides-empty-returns-empty-map
  (is (= {} (sut/compile-overrides nil)))
  (is (= {} (sut/compile-overrides {}))))

(deftest compile-overrides-produces-typed-entry-with-output-type
  (let [result (sut/compile-overrides {'clojure.tools.logging/infof {:output '(s/eq nil)}})]
    (is (= (ab/schema->type (s/eq nil))
           (get-in result ['clojure.tools.logging/infof :output-type])))))

(deftest compile-overrides-symbol-is-key
  (let [result (sut/compile-overrides {'clojure.tools.logging/infof {:output '(s/eq nil)}})]
    (is (contains? result 'clojure.tools.logging/infof))))
