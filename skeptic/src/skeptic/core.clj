(ns skeptic.core
  (:require [skeptic.checking :as checking]
            [skeptic.file :as file]
            [skeptic.colours :as colours]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [skeptic.analysis.annotation :as aa]
            [skeptic.schematize :as schematize]))

(defn get-project-schemas
  [{:keys [verbose show-context namespace] :as opts} root & paths]
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
         (when verbose
           (println "Schema dictionary:")
           (pprint/pprint (schematize/ns-schemas opts ns)))
         (doseq [{:keys [blame path errors context]} (checking/check-ns ns file opts)]
           (println "---------")
           (println (colours/white (str "Namespace: \t\t" ns) true))
           (println (colours/white (str "Expression: \t\t" (pr-str blame)) true))
           (when verbose (println "In macro-expanded path: \t" (pr-str path)))
           (when show-context
             (println "Context:")
             (doseq [[k {:keys [schema resolution-path]}] (:local-vars context)]
               (println (str "\t" (colours/blue (pr-str k) true) ": " (colours/green (pr-str schema))))
               (doseq [{:keys [expr schema]} resolution-path]
                 (println (str "\t\t=> " (colours/blue (pr-str (aa/unannotate-expr expr)) true) ": " (colours/green (pr-str schema))))))
             (doseq [{:keys [expr schema]} (:refs context)]
               (println (str "\t" (colours/blue (pr-str (aa/unannotate-expr expr)) true) " <- " (colours/green (pr-str schema))))))
           (doseq [error errors]
             (reset! errored true)
             (println "---")
             (println error "\n")))
         (catch Exception e
           (println (colours/white (str "Namespace: \t\t" ns) true))
           (println (colours/red (str "Error parsing namespace " ns ": " e) true))
           (when verbose
             (println (stacktrace/print-stack-trace e))))))
      (if @errored
        (System/exit 1)
        (println "No inconsistencies found")))))
