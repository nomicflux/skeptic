(ns leiningen.skeptic-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [leiningen.core.classpath]
            [leiningen.core.eval]
            [leiningen.skeptic :as sut]
            [skeptic.core]
            [skeptic.profiling :as profiling]
            [skeptic.worker.client :as worker-client])
  (:import [java.io ByteArrayOutputStream PrintStream]
           [java.nio.file Files]
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

(defn- no-resolved-jars
  [& _args]
  [])

(deftest lein-task-supplies-project-runtime-worker
  (let [dir (temp-dir!)
        root (.getPath (.getCanonicalFile dir))
        src (str (io/file root "src"))
        project {:root root :source-paths [src] :test-paths []}
        captured (atom nil)]
    (try
      (.mkdirs (io/file src))
      (with-redefs [profiling/run (fn [_opts _target-dir work-fn] (work-fn))
                    skeptic.core/check-project
                    (fn [opts root & source-paths]
                      (reset! captured {:opts opts
                                        :root root
                                        :source-paths source-paths})
                      0)]
        (is (= 0 (#'sut/run-skeptic project []))))
      (is (fn? (get-in @captured [:opts :worker-spawn])))
      (is (= root (:root @captured)))
      (is (= [src] (:source-paths @captured)))
      (finally
        (delete-recursively! dir)))))

(deftest project-runtime-worker-appends-runtime-without-touching-dependencies
  (let [dir (temp-dir!)
        root (.getPath (.getCanonicalFile dir))
        src (str (io/file root "src"))
        cljs-src (str (io/file root "test-cljs"))
        project {:root root
                 :source-paths [src]
                 :test-paths []
                 :dependencies [['org.clojure/clojure "1.12.0"]]
                 :injections ['(register-project-readers!)]
                 :plugins [['lein-doo "0.1.11"]]}
        launch-project (#'sut/worker-project project [src cljs-src])
        worker-form (#'sut/project-worker-form
                     launch-project
                     (io/file root "worker.port"))]
    (try
      (.mkdirs (io/file src))
      (is (= (:dependencies project) (:dependencies launch-project))
          "worker runtime is appended to the launch classpath, never merged into :dependencies")
      (is (some #{cljs-src} (:source-paths launch-project)))
      (is (= (:injections project) (:injections launch-project)))
      (is (some #{'(register-project-readers!)} worker-form))
      (is (= 'skeptic.worker.server/run-worker! (first (last worker-form))))
      (with-redefs [leiningen.core.eval/shell-command
                    (fn [_ _form]
                      ["java" "-classpath" "proj.jar" "clojure.main" "-i" "init.clj"])]
        (let [command (#'sut/worker-launch-command
                       launch-project worker-form ["worker.jar"] ["plugin.jar"])
              entries (str/split (nth command 2)
                                 (re-pattern (java.util.regex.Pattern/quote
                                              java.io.File/pathSeparator)))]
          (is (= ["java" "-classpath"] (subvec command 0 2)))
          (is (= ["proj.jar" "plugin.jar" "worker.jar"] (vec (take 3 entries)))
              "project entries first, then plugin jars, then worker jars")
          (is (= 4 (count entries))
              "tail is Skeptic's own worker source entry")))
      (finally
        (delete-recursively! dir)))))

(deftest project-runtime-worker-owns-output-and-terminates-after-shutdown-failure
  (let [dir (temp-dir!)
        root (.getPath (.getCanonicalFile dir))
        project {:root root :source-paths [] :test-paths []}
        original-out System/out
        captured-out (ByteArrayOutputStream.)
        worker (atom nil)]
    (try
      (with-redefs [leiningen.core.eval/prep (fn [_] nil)
                    leiningen.core.classpath/resolve-managed-dependencies
                    no-resolved-jars
                    leiningen.core.eval/shell-command
                    (fn [_ form]
                      (let [port-path (first (filter string? (flatten form)))]
                        ["/bin/sh" "-c"
                         "printf 4242 > \"$1\"; printf 'worker chatter\\n'; exec sleep 30"
                         "skeptic-test" port-path]))
                    worker-client/connect
                    (fn [_] (throw (ex-info "shutdown transport failed" {})))]
        (System/setOut (PrintStream. captured-out true "UTF-8"))
        (try
          (reset! worker (#'sut/spawn-project-worker! project [] false))
          (finally
            (System/setOut original-out)))
        (is (= 4242 (:port @worker)))
        (is (.isAlive ^Process (:proc @worker)))
        (let [started (System/nanoTime)]
          ((:stop-fn @worker))
          (is (< (/ (- (System/nanoTime) started) 1000000.0) 5000.0)))
        (is (not (.isAlive ^Process (:proc @worker))))
        (is (= ["worker chatter"] @(:stdout-lines @worker)))
        (is (= "" (.toString captured-out "UTF-8"))))
      (finally
        (System/setOut original-out)
        (when (some-> @worker :proc .isAlive)
          (.destroyForcibly ^Process (:proc @worker)))
        (delete-recursively! dir)))))

(deftest project-runtime-worker-reports-child-exit-with-captured-output
  (let [dir (temp-dir!)
        root (.getPath (.getCanonicalFile dir))
        project {:root root :source-paths [] :test-paths []}
        thrown
        (with-redefs [leiningen.core.eval/prep (fn [_] nil)
                      leiningen.core.classpath/resolve-managed-dependencies
                      no-resolved-jars
                      leiningen.core.eval/shell-command
                      (fn [_ _form]
                        ["/bin/sh" "-c"
                         "printf 'launch out\\n'; printf 'launch err\\n' >&2; exit 7"])]
          (try
            (#'sut/spawn-project-worker! project [] false)
            nil
            (catch clojure.lang.ExceptionInfo e
              e)))]
    (try
      (is (some? thrown))
      (is (= 7 (:exit-code (ex-data thrown))))
      (is (= ["launch out"] (:worker-stdout (ex-data thrown))))
      (is (= ["launch err"] (:worker-stderr (ex-data thrown))))
      (is (.contains (.getMessage thrown) "Worker stdout:\nlaunch out"))
      (is (.contains (.getMessage thrown) "Worker stderr:\nlaunch err"))
      (is (not (.exists (io/file (:port-file (ex-data thrown))))))
      (finally
        (delete-recursively! dir)))))
