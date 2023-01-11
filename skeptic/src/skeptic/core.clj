(ns skeptic.core
  (:require [skeptic.checking :as checking]
            [clojure.string :as str])
  (:import [java.io File]))

(defn ns-for-clojure-file
  [^File file]
  (->> file
       slurp
       read-string
       (drop 1)
       first))

;; TODO: confirm working with CLJS & CLJC as well
(defn clojure-files-for-path
  [^String path]
  (->> (File. path)
       file-seq
       (filter #(.isFile ^File %))
       (filter #(re-matches #".*\.clj" (.getName ^File %)))))

(defn relative-path
  [^File root ^String filename]
  (-> (.toURI root)
      (.relativize (.toURI (File. filename)))
      (.getPath)))

(defn get-project-schemas
  [group-name root & paths]
  (let [nss (try (->> paths
                      (map (partial relative-path (File. root)))
                      (mapcat clojure-files-for-path)
                      (map ns-for-clojure-file)
                      sort)
                 (catch Exception e
                   (println "Couldn't get namespaces: " e)
                   (throw e)))]
    (println "Namespaces to check: " (pr-str nss))
    (doseq [ns nss]
      (require ns)
      (println "*** Checking" ns "***")
      ;; (pprint/pprint (checking/annotate-ns ns))
      (try
        (doseq [{:keys [blame path errors]} (checking/check-ns ns)]
          (println "---------")
          (println "Expression: \t" (pr-str blame))
          (println "In: \t\t" (pr-str path))
          (doseq [error errors]
            (println "---")
            (println error "\n")))
        (catch Exception e
          (println "Error parsing namespace" ns ":" e))))))
