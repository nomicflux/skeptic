(ns skeptic.cli.cljs.shadow
  "Source discovery for cljs/cljc files in a shadow-cljs project. Reads
   shadow-cljs.edn at `root` as plain EDN and walks the top-level
   :source-paths key for cljs/cljc files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [skeptic.cli.cljs.discover :as discover])
  (:import [java.io File PushbackReader]))

(defn- shadow-edn-file [root]
  (let [^File f (io/file root "shadow-cljs.edn")]
    (when-not (.exists f)
      (throw (ex-info (str "No shadow-cljs.edn found at " (.getAbsolutePath f))
                      {:root root})))
    f))

(defn- read-shadow-edn [^File f]
  (with-open [r (PushbackReader. (io/reader f))]
    (edn/read r)))

(defn discover-sources
  [root]
  (let [config (read-shadow-edn (shadow-edn-file root))
        source-paths (mapv #(discover/absolutize root %) (:source-paths config))
        {:keys [cljs-files cljc-files]} (discover/discover-cljs-and-cljc source-paths)]
    {:source-paths source-paths
     :cljs-files cljs-files
     :cljc-files cljc-files}))
