(ns skeptic.core
  (:require [skeptic.checking :as checking]
            [skeptic.file :as file]
            [skeptic.colours :as colours]
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
           (println (colours/white (str "Namespace: \t\t" ns) true))
           (println (colours/white (str "Expression: \t\t" (pr-str blame)) true))
           (println "In macro-expanded path: \t" (pr-str path))
           (when verbose
             (println "Context:")
             (doseq [[k {:keys [schema resolution-path]}] (:local-vars context)]
               (println (str "\t" (colours/blue (pr-str k)) ": " (colours/green (pr-str schema))))
               (doseq [{:keys [expr schema]} resolution-path]
                 (println (str "\t\t=> " (colours/blue (pr-str (analysis/unannotate-expr expr))) ": " (colours/green (pr-str schema))))))
             (doseq [{:keys [expr schema]} (:refs context)]
               (println (str "\t" (colours/blue (pr-str (analysis/unannotate-expr expr))) " <- " (colours/green (pr-str schema))))))
           (doseq [error errors]
             (reset! errored true)
             (println "---")
             (println error "\n")))
         (catch Exception e
           (println (colours/red (str "Error parsing namespace" ns ":" e) true)))))
      (if @errored
        (System/exit 1)
        (println "No inconsistencies found")))))
