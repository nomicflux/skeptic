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
                    (fn [worker-jars project-cp]
                      (reset! builder-input {:worker-jars worker-jars
                                             :project-cp project-cp})
                      {:combined "combined-cp"})
                    profiling/run (fn [_opts _target-dir work-fn] (work-fn))
                    skeptic.core/check-project
                    (fn [opts root & source-paths]
                      (reset! captured {:opts opts
                                        :root root
                                        :source-paths source-paths})
                      0)]
        (is (= 0 (#'sut/run-skeptic project []))))
      (is (= project @classpath-project))
      ;; Discovered cljs source-paths are concatenated into project-cp so the
      ;; cljs analyzer's resolver (locate-src → util/ns->source → io/resource)
      ;; can find sibling required namespaces. lein's `get-classpath` honors
      ;; only :source-paths/:test-paths/:resource-paths, so cljsbuild
      ;; source-paths (and any other discovery contribution) must be added
      ;; explicitly here.
      (is (= ["project-cp" src] (:project-cp @builder-input)))
      (is (vector? (:worker-jars @builder-input))
          "lein entrypoint resolves worker jars via lein aether before calling worker-classpath-entries")
      (is (seq (:worker-jars @builder-input))
          "resolved worker jars must be non-empty (10 worker deps + transitives)")
      (is (= {:combined "combined-cp"}
             (get-in @captured [:opts :worker-classpath])))
      (is (= root (:root @captured)))
      (is (= [src] (:source-paths @captured)))
      (finally
        (delete-recursively! dir)))))

(deftest plugin-jars-are-added-to-project-cp
  (let [dir (temp-dir!)
        root (.getPath (.getCanonicalFile dir))
        src (str (io/file root "src"))
        project {:root root :source-paths [src] :test-paths []
                 :plugins [['lein-doo "0.1.11"]]}
        plugin-jar (io/file "/tmp/skeptic-test-fake-plugin.jar")
        base-cp-marker "/tmp/skeptic-test-fake-base.jar"
        builder-input (atom nil)]
    (try
      (.mkdirs (io/file src))
      (with-redefs [leiningen.core.classpath/get-classpath
                    (fn [_project] [base-cp-marker])
                    leiningen.core.classpath/resolve-managed-dependencies
                    (fn [deps-key _managed-key project-arg]
                      (cond
                        ;; Plugin resolution path: return the fake plugin jar
                        (and (= :plugins deps-key) (= project project-arg))
                        [plugin-jar]
                        ;; Worker-deps resolution path: return empty so the
                        ;; synthetic-project path doesn't hit Maven.
                        (= :dependencies deps-key)
                        []
                        :else
                        (throw (ex-info "unexpected resolve-managed-dependencies call"
                                        {:deps-key deps-key :project project-arg}))))
                    worker-classpath/worker-classpath-entries
                    (fn [_worker-jars project-cp]
                      (reset! builder-input project-cp)
                      {:combined "combined-cp"})
                    profiling/run (fn [_opts _target-dir work-fn] (work-fn))
                    skeptic.core/check-project (fn [_opts _root & _source-paths] 0)]
        (is (= 0 (#'sut/run-skeptic project []))))
      (is (some #{base-cp-marker} @builder-input)
          "base classpath entries must still be present")
      (is (some #{(.getAbsolutePath plugin-jar)} @builder-input)
          "plugin jars must be added to project-cp so plugin-declared cljs deps resolve")
      (finally
        (delete-recursively! dir)))))
