(ns skeptic.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [schema.core]
            [skeptic.analysis.bridge :as ab])
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

(create-ns 'skeptic.config.eval-ns)
(binding [*ns* (find-ns 'skeptic.config.eval-ns)]
  (eval '(clojure.core/require '[schema.core :as s])))

(defn- eval-schema-form [form]
  (binding [*ns* (find-ns 'skeptic.config.eval-ns)]
    (eval form)))

(defn- override->entry
  [[sym {:keys [schema]}]]
  [sym (ab/schema->type (eval-schema-form schema))])

(defn compile-overrides
  [raw-overrides]
  (if (empty? raw-overrides)
    {}
    (into {} (map override->entry) raw-overrides)))
