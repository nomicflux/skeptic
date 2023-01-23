(ns skeptic.core
  (:require [skeptic.checking :as checking]
            [skeptic.file :as file]
            [clojure.java.io :as io]
            [skeptic.analysis :as analysis]))

(defn get-project-schemas
  [{:keys [verbose namespace] :as opts} root & paths]
  (let [nss (cond-> (try (->> paths
                              (map (partial file/relative-path (io/file root)))
                              (mapcat file/clojure-files-for-path)
                              (map file/ns-for-clojure-file)
                              (remove (comp nil? first))
                              (into {}))
                         (catch Exception e
                           (println "Couldn't get namespaces: " e)
                           (throw e)))

              namespace
              (select-keys [(symbol namespace)]))]
    (when verbose (println "Namespaces to check: " (pr-str (keys nss))))
    (let [errored (atom false)]
      (doseq [[ns file] nss]
       (require ns)
       (when verbose (println "*** Checking" ns "***"))
       ;; (pprint/pprint (checking/annotate-ns ns))
       (try
         (doseq [{:keys [blame path errors context]} (checking/check-ns ns file opts)]
           (println "---------")
           (println "Namespace: \t\t" ns)
           (println "Expression: \t\t" (pr-str blame))
           (println "In macro-expanded path: \t" (pr-str path))
           (when verbose
             (println "Context:")
             (doseq [[k {:keys [schema resolution-path]}] context]
               (println "\t" k ":" (pr-str schema))
               (doseq [{:keys [expr schema]} resolution-path]
                 (println "\t\t=>" (analysis/unannotate-expr expr) ":" schema))))
           (doseq [error errors]
             (reset! errored true)
             (println "---")
             (println error "\n")))
         (catch Exception e
           (println "Error parsing namespace" ns ":" e))))
      (if @errored
        (System/exit 1)
        (println "No inconsistencies found")))))
