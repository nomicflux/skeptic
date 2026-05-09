(ns skeptic.cli.cljs.discover
  "Walk a collection of source roots and partition the matching files into
   cljs and cljc buckets. Paths that do not exist are skipped silently;
   paths that point at a regular file are included if their extension matches."
  (:require [clojure.java.io :as io])
  (:import [java.io File]))

(defn absolutize
  "If `path` is already absolute, return it unchanged; otherwise resolve it
   against `root`."
  [root path]
  (let [^File f (io/file path)]
    (.getPath (if (.isAbsolute f) f (io/file root path)))))

(defn- ext-match? [^File f ^String suffix]
  (.endsWith (.getName f) suffix))

(defn discover-cljs-and-cljc
  [paths]
  (let [files (->> paths
                   (mapcat (fn [p]
                             (let [f (io/file p)]
                               (when (.exists f) (file-seq f)))))
                   (filter (fn [^File f] (.isFile f))))]
    {:cljs-files (vec (filter #(ext-match? % ".cljs") files))
     :cljc-files (vec (filter #(ext-match? % ".cljc") files))}))
