(ns skeptic.tool-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.cli.cljs.shadow :as shadow]
            [skeptic.cli.main :as main]
            [skeptic.cli.paths :as paths]
            [skeptic.core :as core]
            [skeptic.profiling :as profiling]
            [skeptic.tool :as tool])
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

(deftest tool-check-treats-shadow-aliases-as-basis-input
  (let [dir (temp-dir!)
        root (canonical-file dir)
        aliases (atom nil)
        captured-paths (atom nil)]
    (try
      (spit (io/file dir "deps.edn") "{}")
      (spit (io/file dir "shadow-cljs.edn") "{}")
      (with-redefs [paths/discover-paths (fn [root basis-aliases]
                                           (reset! aliases {:root root :aliases basis-aliases})
                                           [(str (io/file root "src"))
                                            (str (io/file root "shadow-dep-src"))])
                    shadow/deps-aliases (fn [_root] [:shadow :dev])
                    shadow/discover-sources (fn [_root] {:source-paths ["shadow-src"]})
                    profiling/run (fn [_opts _target-dir work-fn] (work-fn))
                    core/check-project (fn [_opts _root & source-paths]
                                         (reset! captured-paths source-paths)
                                         0)]
        (is (= 0 (main/check-project {:project-dir (.getPath dir)
                                      :alias ":dev"}))))
      (is (= (.getPath root) (:root @aliases)))
      (is (= [:dev :shadow] (:aliases @aliases)))
      (is (= [(str (io/file root "src"))
              (str (io/file root "shadow-dep-src"))
              (str (io/file root "shadow-src"))]
             @captured-paths))
      (finally
        (delete-recursively! dir)))))
