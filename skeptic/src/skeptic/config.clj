(ns skeptic.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file FileSystems Paths]))

(defn load-raw-config
  [root]
  (let [f (io/file root ".skeptic" "config.edn")]
    (if (.exists f)
      (edn/read-string (slurp f))
      {})))

(defn- canonical ^File [x]
  (.getCanonicalFile (io/file x)))

(defn- rel-path [root ^File file]
  (str (.relativize (.toPath (canonical root)) (.toPath (canonical file)))))

(defn- glob-matches? [pattern rel]
  (.matches (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))
            (Paths/get rel (into-array String []))))

(defn path-excluded?
  [root patterns ^File file]
  (boolean (when (seq patterns)
             (some #(glob-matches? % (rel-path root file)) patterns))))
