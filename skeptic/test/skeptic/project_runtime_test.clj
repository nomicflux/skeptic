(ns skeptic.project-runtime-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skeptic.project-runtime :as sut])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-project-runtime-test-"
                                      (into-array FileAttribute []))))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(deftest with-project-runtime-resolves-project-only-resources-temporarily
  (let [dir (temp-dir!)
        root (.getCanonicalFile dir)
        src (io/file root "src")
        ns-dir (io/file src "project_only")
        source (io/file ns-dir "runtime_probe.clj")
        resource-path "project_only/runtime_probe.clj"
        ns-sym 'project-only.runtime-probe]
    (try
      (.mkdirs ns-dir)
      (spit source "(ns project-only.runtime-probe)\n(def value 42)\n")
      (remove-ns ns-sym)
      (let [runtime (sut/build-runtime (.getPath root) [] [(.getPath src)])
            before-loader (.getContextClassLoader (Thread/currentThread))
            before-classpath (System/getProperty "java.class.path" "")]
        (testing "project source is not visible through the ambient implementation loader"
          (is (nil? (io/resource resource-path))))
        (testing "project source is visible only inside the project runtime"
          (sut/with-project-runtime
           runtime
           #(do
              (is (some? (io/resource resource-path)))
              (require ns-sym)
              (is (= 42 @(resolve 'project-only.runtime-probe/value))))))
        (testing "global classpath and context loader are restored"
          (is (= before-classpath (System/getProperty "java.class.path" "")))
          (is (identical? before-loader (.getContextClassLoader (Thread/currentThread))))
          (is (nil? (io/resource resource-path)))))
      (finally
        (remove-ns ns-sym)
        (delete-recursively! dir)))))
