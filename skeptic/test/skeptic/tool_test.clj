(ns skeptic.tool-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skeptic.cli.cljs.shadow :as shadow]
            [skeptic.cli.main :as main]
            [skeptic.cli.paths :as paths]
            [skeptic.core :as core]
            [skeptic.profiling :as profiling]
            [skeptic.tool :as tool]
            [skeptic.worker.classpath :as worker-classpath])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-tool-test-"
                                      (into-array FileAttribute []))))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(defn- canonical-file
  [^java.io.File f]
  (.getCanonicalFile f))

(deftest tool-api-namespace-exposes-check-function
  (is (var? #'tool/check)))

(deftest tool-check-uses-project-paths-as-input-without-mutating-classpath
  (let [dir (temp-dir!)
        root (canonical-file dir)
        captured (atom nil)
        before-cp (System/getProperty "java.class.path" "")]
    (try
      (with-redefs [profiling/run (fn [_opts _target-dir work-fn] (work-fn))
                    core/check-project (fn [opts root & source-paths]
                                         (reset! captured
                                                 {:opts opts
                                                  :root root
                                                  :source-paths source-paths
                                                  :classpath (System/getProperty "java.class.path" "")})
                                         0)]
        (is (= 0 (main/check-project {:project-dir (.getPath dir)
                                      :paths "src,test"
                                      :namespace 'demo.core}))))
      (is (= before-cp (:classpath @captured)))
      (is (not (str/includes? (:classpath @captured) (.getPath dir))))
      (is (= (.getPath root) (:root @captured)))
      (is (= [(str (io/file root "src"))
              (str (io/file root "test"))]
             (:source-paths @captured)))
      (is (= ["demo.core"] (get-in @captured [:opts :namespace])))
      (finally
        (delete-recursively! dir)))))

(deftest tool-check-discovery-and-classpath-share-one-basis
  ;; The project resolves under one alias set: the user --alias plus the
  ;; deps.edn aliases its shadow-cljs.edn declares as its deps source. Both
  ;; source-path discovery and the worker classpath must be resolved under
  ;; that SAME set, so Skeptic reads the project under the basis it compiles
  ;; under. Discovery does not separately concatenate shadow-cljs.edn source
  ;; paths — they ride the basis via the activated aliases' :extra-paths.
  (let [dir (temp-dir!)
        root (canonical-file dir)
        project-context-call (atom nil)
        worker-builder-input (atom nil)
        captured-worker-cp (atom nil)
        captured-paths (atom nil)]
    (try
      (spit (io/file dir "deps.edn") "{}")
      (spit (io/file dir "shadow-cljs.edn") "{}")
      (with-redefs [paths/project-context (fn [root aliases]
                                            (reset! project-context-call {:root root :aliases aliases})
                                            {:basis ::single-basis
                                             :source-paths [(str (io/file root "src"))]
                                             :classpath-entries ["project-cp"]})
                    worker-classpath/worker-classpath-entries (fn [worker-jars project-cp]
                                                                 (reset! worker-builder-input {:worker-jars worker-jars
                                                                                               :project-cp project-cp})
                                                                 {:combined "combined-cp"})
                    shadow/deps-aliases (fn [_root] [:shadow :sci])
                    profiling/run (fn [_opts _target-dir work-fn] (work-fn))
                    core/check-project (fn [opts _root & source-paths]
                                         (reset! captured-worker-cp (:worker-classpath opts))
                                         (reset! captured-paths source-paths)
                                         0)]
        (is (= 0 (main/check-project {:project-dir (.getPath dir)
                                      :alias ":dev"}))))
      (testing "discovery aliases = user --alias plus shadow-cljs.edn-declared aliases"
        (is (= (.getPath root) (:root @project-context-call)))
        (is (= [:dev :shadow :sci] (:aliases @project-context-call))))
      (testing "the deps entrypoint delegates worker cp assembly to the shared builder"
        ;; Discovered cljs source-paths are concatenated into project-cp so the
        ;; cljs analyzer's resolver (locate-src → util/ns->source → io/resource)
        ;; can find sibling required namespaces on the worker classpath. For
        ;; plain deps.edn the basis classpath already covers :paths; distinct
        ;; dedups. For shadow-cljs source dirs outside the basis, this is what
        ;; makes them visible to the analyzer.
        (is (= ["project-cp" (str (io/file root "src"))]
               (:project-cp @worker-builder-input)))
        (is (vector? (:worker-jars @worker-builder-input))
            "deps entrypoint resolves worker jars via tools.deps before calling worker-classpath-entries")
        (is (seq (:worker-jars @worker-builder-input))
            "resolved worker jars must be non-empty (10 worker deps + transitives)")
        (is (= {:combined "combined-cp"} @captured-worker-cp)))
      (testing "source paths come only from the basis, no shadow-cljs.edn concat"
        (is (= [(str (io/file root "src"))] @captured-paths)))
      (finally
        (delete-recursively! dir)))))
