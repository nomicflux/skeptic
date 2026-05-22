(ns skeptic.cli.cljs.shadow-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skeptic.cli.cljs.shadow :as sut])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def fixture-root "dev-resources/cljs-fixtures/p2-shadow")

(defn- file-names [files]
  (set (map (fn [^File f] (.getName f)) files)))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-shadow-"
                                      (into-array FileAttribute []))))

(defn- write-shadow!
  [dir config]
  (spit (io/file dir "shadow-cljs.edn") (pr-str config)))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(deftest discover-sources-reads-shadow-cljs-edn-and-walks-source-paths
  (let [{:keys [source-paths cljs-files cljc-files]}
        (sut/discover-sources fixture-root)]
    (testing "source-paths is exactly the one configured src"
      (is (= 1 (count source-paths))))
    (testing "cljs-files = foo.cljs"
      (is (= #{"foo.cljs"} (file-names cljs-files))))
    (testing "cljc-files = bar.cljc"
      (is (= #{"bar.cljc"} (file-names cljc-files))))
    (testing "jvm.clj is not in either bucket"
      (is (not (contains? (file-names cljs-files) "jvm.clj")))
      (is (not (contains? (file-names cljc-files) "jvm.clj"))))))

(deftest discover-sources-throws-when-shadow-cljs-edn-missing
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No shadow-cljs.edn found"
                        (sut/discover-sources "dev-resources/cljs-fixtures/p2-deps"))))

(deftest deps-aliases-reads-shadow-deps-aliases
  (is (= [:shadow :sci :cherry]
         (sut/deps-aliases "dev-resources/cljs-fixtures/p12-shadow-runtime"))))

(deftest deps-aliases-defaults-to-empty
  (testing "missing shadow config"
    (is (= [] (sut/deps-aliases "dev-resources/cljs-fixtures/p2-deps"))))
  (testing "shadow config without deps aliases"
    (is (= [] (sut/deps-aliases fixture-root))))
  (testing "shadow config with :deps true"
    (let [dir (temp-dir!)]
      (try
        (write-shadow! dir {:source-paths ["src"] :deps true})
        (is (= [] (sut/deps-aliases (.getAbsolutePath dir))))
        (finally
          (delete-recursively! dir)))))
  (testing "shadow config with :deps false"
    (let [dir (temp-dir!)]
      (try
        (write-shadow! dir {:source-paths ["src"] :deps false})
        (is (= [] (sut/deps-aliases (.getAbsolutePath dir))))
        (finally
          (delete-recursively! dir))))))
