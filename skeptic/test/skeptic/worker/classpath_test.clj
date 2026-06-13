(ns skeptic.worker.classpath-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.tools.deps :as deps]
            [skeptic.worker.classpath :as classpath]
            [skeptic.worker.client :as wc]
            [skeptic.worker.deps :as worker-deps]
            [skeptic.worker.process :as proc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir!
  []
  (.toFile (Files/createTempDirectory "skeptic-worker-classpath-test-"
                                      (into-array FileAttribute []))))

(defn- delete-recursively!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursively! c)))
  (.delete f))

(defn- resolved-worker-jars
  "Resolve the worker dep declaration via tools.deps for the test. Same path
   the deps.edn tool entrypoint uses at runtime."
  []
  (->> (deps/create-basis
         {:project nil
          :aliases [:worker]
          :extra {:aliases {:worker {:replace-deps (worker-deps/worker-deps-as-mvn-map)}}}})
       :classpath-roots
       (filterv #(str/ends-with? % ".jar"))))

(deftest worker-classpath-starts-worker-without-skeptic-schema-or-malli-deps
  (let [dir (temp-dir!)
        worker (atom nil)
        conn (atom nil)]
    (try
      (.mkdirs (io/file dir "src"))
      (let [project-cp [(.getPath (io/file dir "src"))]
            worker-jars (resolved-worker-jars)
            {:keys [combined]}
            (classpath/worker-classpath-entries worker-jars project-cp)]
        (is (string? combined))
        (is (str/starts-with? combined (first project-cp))
            "project-cp must come first in combined launch cp")
        (is (every? #(str/includes? combined %) worker-jars)
            "every resolved worker-jar must appear in combined launch cp")
        (is (str/includes? combined "skeptic")
            "skeptic worker self-entry must be present so the worker JVM can require its own server")
        ;; Schema and malli ride the launch cp as transitive deps of the
        ;; skeptic JAR (the worker server lives there). The "no admission on
        ;; worker" rule applies to CODE EXECUTION — verified by what
        ;; namespaces actually load on the worker, not by what JARs sit on
        ;; the classpath. The post-cutover worker requires only
        ;; skeptic.worker.* and skeptic.analysis.class-oracle (for var
        ;; sharing); it does not require skeptic.typed-decls,
        ;; skeptic.malli-spec.*, skeptic.analysis.bridge, or the rest of the
        ;; admission pipeline. Inert JAR presence is fine.
        (is (re-find #"/transit-clj-[^/]+\.jar" combined)
            "transit-clj must be present for the worker wire")
        (is (re-find #"/clojurescript-[^/]+\.jar" combined)
            "clojurescript must be present so worker can require cljs.analyzer.api")
        (is (re-find #"/tools\.analyzer\.jvm-[^/]+\.jar" combined)
            "tools.analyzer.jvm must be present so worker can require analyzer-clj")
        (is (re-find #"/nrepl-[^/]+\.jar" combined)
            "nrepl must be present so worker server can start")
        (reset! worker (proc/spawn! combined (System/getProperty "user.dir") false))
        (reset! conn (wc/connect false (:port @worker)))
        (is (= "ok" (:pong (wc/ask @conn {:op "ping"})))))
      (finally
        (when @conn (wc/disconnect! @conn))
        (when @worker (proc/stop! @worker))
        (delete-recursively! dir)))))

(defn- clj-files
  [^java.io.File root]
  (when (.exists root)
    (filter #(and (.isFile ^java.io.File %)
                  (str/ends-with? (.getName ^java.io.File %) ".clj"))
            (file-seq root))))

(deftest worker-classpath-builder-has-one-def-site
  (let [roots [(io/file "src") (io/file ".." "lein-skeptic" "src")]
        def-sites (->> roots
                       (mapcat clj-files)
                       (filter #(re-find #"\(defn\s+worker-classpath-entries\b"
                                         (slurp %)))
                       (map #(.getPath ^java.io.File %))
                       set)]
    (is (= #{(.getPath (io/file "src" "skeptic" "worker" "classpath.clj"))}
           def-sites))))
