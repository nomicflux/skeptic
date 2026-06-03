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

(defn- cp-string
  [entries]
  (str/join java.io.File/pathSeparator entries))

(deftest worker-classpath-starts-worker-without-skeptic-schema-or-malli-deps
  (let [dir (temp-dir!)
        worker (atom nil)
        conn (atom nil)]
    (try
      (.mkdirs (io/file dir "src"))
      (let [project-cp [(.getPath (io/file dir "src"))]
            {:keys [runtime project]} (classpath/worker-classpath-entries project-cp)]
        (is (= project-cp project))
        (is (not-any? #{(first project-cp)} runtime))
        (is (not-any? #(re-find #"/schema-[^/]+\.jar$" %) runtime))
        (is (not-any? #(re-find #"/malli-[^/]+\.jar$" %) runtime))
        (reset! worker (proc/spawn! (cp-string runtime) (cp-string project)))
        (reset! conn (wc/connect (:port @worker)))
        (is (= "ok" (:pong (wc/ask @conn {:op "ping"})))))
      (finally
        (when @conn (wc/disconnect! @conn))
        (when @worker (proc/stop! @worker))
        (delete-recursively! dir)))))

(deftest worker-runtime-boots-with-project-clojure-runtime
  (let [dir (temp-dir!)]
    (try
      (let [language-dir (io/file dir "language")
            project-lib-dir (io/file dir "project-lib")]
        (doseq [resource ["clojure/main.clj"
                          "clojure/spec/alpha.clj"
                          "clojure/core/specs/alpha.clj"]]
          (.mkdirs (.getParentFile (io/file language-dir resource)))
          (spit (io/file language-dir resource) ""))
        (.mkdirs (io/file project-lib-dir "taoensso"))
        (spit (io/file project-lib-dir "taoensso" "encore.cljc") "")
        (let [language-path (.getPath language-dir)
              project-lib-path (.getPath project-lib-dir)
              {:keys [runtime project]}
              (classpath/worker-classpath-entries [language-path project-lib-path])]
          (is (some #{language-path} project))
          (is (some #{project-lib-path} project))
          (is (= language-path (first runtime)))
          (is (not-any? #{project-lib-path} runtime))))
      (finally
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
