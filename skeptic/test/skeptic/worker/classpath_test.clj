(ns skeptic.worker.classpath-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.worker.classpath :as classpath]
            [skeptic.worker.client :as wc]
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

(deftest worker-classpath-starts-worker-without-skeptic-schema-or-malli-deps
  (let [dir (temp-dir!)
        worker (atom nil)
        conn (atom nil)]
    (try
      (.mkdirs (io/file dir "src"))
      (let [project-cp [(.getPath (io/file dir "src"))]
            entries (classpath/worker-classpath-entries project-cp)
            cp (str/join java.io.File/pathSeparator entries)]
        (is (some #{(first project-cp)} entries))
        (is (not-any? #(re-find #"/schema-[^/]+\.jar$" %) entries))
        (is (not-any? #(re-find #"/malli-[^/]+\.jar$" %) entries))
        (reset! worker (proc/spawn! cp))
        (reset! conn (wc/connect (:port @worker)))
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
