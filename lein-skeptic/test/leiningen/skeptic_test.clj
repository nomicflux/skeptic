(ns leiningen.skeptic-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [leiningen.core.classpath]
            [leiningen.skeptic :as sut]
            [skeptic.core]
            [skeptic.profiling :as profiling]
            [skeptic.worker.classpath :as worker-classpath])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "lein-skeptic-test-"
                                      (into-array FileAttribute []))))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(deftest lein-task-uses-shared-worker-classpath-builder
  (let [dir (temp-dir!)
        root (.getPath (.getCanonicalFile dir))
        src (str (io/file root "src"))
        project {:root root :source-paths [src] :test-paths []}
        classpath-project (atom nil)
        builder-input (atom nil)
        captured (atom nil)]
    (try
      (.mkdirs (io/file src))
      (with-redefs [leiningen.core.classpath/get-classpath
                    (fn [project]
                      (reset! classpath-project project)
                      ["project-cp"])
                    worker-classpath/worker-classpath-entries
                    (fn [project-cp]
                      (reset! builder-input project-cp)
                      {:runtime ["runtime-cp"]
                       :project ["project-cp"]})
                    profiling/run (fn [_opts _target-dir work-fn] (work-fn))
                    skeptic.core/check-project
                    (fn [opts root & source-paths]
                      (reset! captured {:opts opts
                                        :root root
                                        :source-paths source-paths})
                      0)]
        (is (= 0 (#'sut/run-skeptic project []))))
      (is (= project @classpath-project))
      (is (= ["project-cp"] @builder-input))
      (is (= {:runtime ["runtime-cp"] :project ["project-cp"]}
             (get-in @captured [:opts :worker-classpath])))
      (is (= root (:root @captured)))
      (is (= [src] (:source-paths @captured)))
      (finally
        (delete-recursively! dir)))))
