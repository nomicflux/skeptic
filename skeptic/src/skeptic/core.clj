(ns skeptic.core
  (:require [clojure.java.io :as io]
            [skeptic.checking.pipeline :as checking]
            [skeptic.config :as config]
            [skeptic.file :as file]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.output :as output]))

(defn- discover-project-files
  [root paths]
  (reduce (fn [{:keys [files failures]} path]
            (let [{new-files :files
                   new-failures :failures}
                  (file/discover-clojure-files (file/relative-path (io/file root) path))]
              {:files (into files new-files)
               :failures (into failures new-failures)}))
          {:files []
           :failures []}
          paths))

(defn- blocking-discovery-failures
  [namespace discovered-nss failures]
  (cond
    (empty? failures) []
    namespace (if (contains? discovered-nss (symbol namespace))
                []
                failures)
    :else failures))

(defn- failure->info
  [{:keys [path exception]}]
  {:path path
   :message (or (.getMessage ^Exception exception)
                (str exception))})

(defn check-project
  [{:keys [namespace] :as opts} root & paths]
  (let [raw-config (config/load-raw-config root)
        type-overrides (config/compile-overrides (:type-overrides raw-config))
        opts (assoc opts :skeptic/config raw-config :skeptic/type-overrides type-overrides)
        {:keys [files failures]} (discover-project-files root paths)
        files (remove #(config/path-excluded? root (:exclude-files raw-config) %) files)
        discovered-nss (try (->> files
                                 (map file/ns-for-clojure-file)
                                 (remove (comp nil? first))
                                 (into {}))
                            (catch Exception e
                              (println "Couldn't get namespaces: " e)
                              (throw e)))
        blocking-failures (blocking-discovery-failures namespace discovered-nss failures)
        _ (when (seq blocking-failures)
            (doseq [failure blocking-failures]
              (println "Couldn't get namespaces:"
                       (format "%s (%s)"
                               (:path failure)
                               (or (some-> ^Exception (:exception failure) .getMessage)
                                   (str (:exception failure))))))
            (throw (ex-info "Couldn't get namespaces"
                            {:failures blocking-failures})))
        nss (cond-> discovered-nss
              namespace
              (select-keys [(symbol namespace)]))
        {:keys [run-start discovery-warn ns-start finding ns-end run-end]}
        (output/printer opts)
        totals (atom {:finding-count 0
                      :exception-count 0
                      :namespace-count (count nss)
                      :namespaces-with-findings 0})]
    (run-start opts nss)
    (doseq [failure failures]
      (discovery-warn (failure->info failure)))
    (let [errored (atom false)]
      (doseq [[ns source-file] nss]
        (ns-start ns source-file opts)
        (let [ns-findings (atom 0)]
          (doseq [result (checking/check-namespace opts ns source-file)]
            (let [summary (inrep/report-summary result)
                  exception? (= :exception (:report-kind summary))]
              (finding ns result summary opts)
              (reset! errored true)
              (swap! ns-findings inc)
              (swap! totals update
                     (if exception? :exception-count :finding-count)
                     inc)))
          (when (pos? @ns-findings)
            (swap! totals update :namespaces-with-findings inc))
          (ns-end ns @ns-findings opts)))
      (run-end @errored @totals)
      (if @errored 1 0))))
