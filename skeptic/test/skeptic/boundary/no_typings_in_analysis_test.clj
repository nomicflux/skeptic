(ns skeptic.boundary.no-typings-in-analysis-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- clj-file?
  [^java.io.File f]
  (and (.isFile f) (.endsWith (.getName f) ".clj")))

(defn- find-hits
  [^java.io.File root pattern]
  (->> (file-seq root)
       (filter clj-file?)
       (keep (fn [^java.io.File f]
               (when (re-find pattern (slurp f))
                 (.getPath f))))))

(deftest analysis-namespaces-do-not-carry-typings-sidecar
  (let [hits (find-hits (io/file "src/skeptic/analysis") #":typings\b")]
    (is (empty? hits)
        (str ":typings sidecar key found in analysis sources: "
             (pr-str hits)))))
