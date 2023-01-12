(ns skeptic.core
  (:require [skeptic.checking :as checking]
            [skeptic.file :as file]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn get-project-schemas
  [{:keys [verbose] :as opts} root & paths]
  (let [nss (try (->> paths
                      (map (partial file/relative-path (io/file root)))
                      (mapcat file/clojure-files-for-path)
                      (map file/ns-for-clojure-file)
                      (remove (comp nil? first))
                      (into {}))
                 (catch Exception e
                   (println "Couldn't get namespaces: " e)
                   (throw e)))]
    (when verbose (println "Namespaces to check: " (pr-str (keys nss))))
    (let [errored (atom false)]
      (doseq [[ns file] nss]
       (require ns)
       (when verbose (println "*** Checking" ns "***"))
       ;; (pprint/pprint (checking/annotate-ns ns))
       (try
         (doseq [{:keys [blame path errors]} (checking/check-ns ns file opts)]
           (println "---------")
           (println "Expression: \t" (pr-str blame))
           (println "In: \t\t" (pr-str path))
           (doseq [error errors]
             (reset! errored true)
             (println "---")
             (println error "\n")))
         (catch Exception e
           (println "Error parsing namespace" ns ":" e))
         (finally
           (when @errored
             (System/exit 1))))))))
