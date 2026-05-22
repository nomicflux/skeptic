(ns skeptic.cli.project-classpath-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.cli.project-classpath :as sut])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-project-classpath-"
                                      (into-array FileAttribute []))))

(defn- write-edn!
  [dir name value]
  (spit (io/file dir name) (pr-str value)))

(defn- mkdirs!
  [dir path]
  (.mkdirs (io/file dir path)))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(deftest runtime-aliases-combines-explicit-and-shadow-aliases
  (let [dir (temp-dir!)]
    (try
      (write-edn! dir "shadow-cljs.edn" {:source-paths ["src"]
                                         :deps {:aliases [:shadow :sci :shadow :cherry]}})
      (is (= [:dev :shadow :sci :cherry]
             (sut/runtime-aliases (.getAbsolutePath dir) {:alias [:dev :shadow]})))
      (finally
        (delete-recursively! dir)))))

(deftest merge-classpath-entries-is-stable-and-deduped
  (is (= ["project-a" "project-b" "current-c"]
         (sut/merge-classpath-entries ["project-a" "project-b"]
                                      ["project-b" "current-c"]))))

(deftest project-classpath-command-builds-child-command-on-first-pass
  (let [dir (temp-dir!)]
    (try
      (mkdirs! dir "src")
      (mkdirs! dir "shadow-src")
      (write-edn! dir "deps.edn" {:paths ["src"]
                                  :aliases {:shadow {:extra-paths ["shadow-src"]}}})
      (write-edn! dir "shadow-cljs.edn" {:source-paths ["src"]
                                         :deps {:aliases [:shadow]}})
      (with-redefs [sut/current-classpath-entries (constantly ["current-a" "current-b"])
                    sut/java-executable (constantly "java-test")
                    sut/ready? (constantly false)]
        (let [{:keys [command directory aliases classpath]}
              (sut/project-classpath-command (.getAbsolutePath dir) {} ["--verbose"])
              cp (nth command 3)]
          (is (= [:shadow] aliases))
          (is (= (.getAbsoluteFile dir) directory))
          (is (= ["java-test"
                  (str "-D" sut/ready-property "=true")
                  "-cp"]
                 (subvec command 0 3)))
          (is (= ["clojure.main" "-m" "skeptic.cli.main" "--verbose"]
                 (subvec command 4)))
          (is (= cp (sut/classpath-string classpath)))
          (is (some #(str/ends-with? % "src") classpath))
          (is (some #(str/ends-with? % "shadow-src") classpath))
          (is (some #{"current-a"} classpath))
          (is (some #{"current-b"} classpath))))
    (finally
      (delete-recursively! dir)))))

(deftest project-classpath-command-is-disabled-in-child-process
  (let [dir (temp-dir!)]
    (try
      (write-edn! dir "deps.edn" {:paths ["src"]})
      (write-edn! dir "shadow-cljs.edn" {:source-paths ["src"]
                                         :deps {:aliases [:shadow]}})
      (with-redefs [sut/ready? (constantly true)]
        (is (nil? (sut/project-classpath-command (.getAbsolutePath dir) {} []))))
      (finally
        (delete-recursively! dir)))))

(deftest project-classpath-command-is-not-needed-without-runtime-aliases
  (let [dir (temp-dir!)]
    (try
      (write-edn! dir "deps.edn" {:paths ["src"]})
      (is (nil? (sut/project-classpath-command (.getAbsolutePath dir) {} [])))
      (finally
        (delete-recursively! dir)))))
