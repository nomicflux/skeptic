(ns skeptic.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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
  [requested-namespaces discovered-nss failures]
  (cond
    (empty? failures) []
    (seq requested-namespaces) (if (every? (set (keys discovered-nss))
                                            requested-namespaces)
                                 []
                                 failures)
    :else failures))

(defn- failure->info
  [{:keys [path exception]}]
  {:path path
   :message (or (.getMessage ^Exception exception)
                (str exception))})

(defn- expand-namespace-args
  [raw]
  (->> raw
       (mapcat #(str/split % #","))
       (map str/trim)
       (remove str/blank?)
       (mapv symbol)))

(defn check-project
  [{raw-namespaces :namespace :as opts} root & paths]
  (let [requested-namespaces (when (seq raw-namespaces)
                               (expand-namespace-args raw-namespaces))
        raw-config (config/load-raw-config root)
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
        blocking-failures (blocking-discovery-failures requested-namespaces
                                                       discovered-nss
                                                       failures)
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
              (seq requested-namespaces)
              (select-keys requested-namespaces))
        opts (assoc opts :project-state
                    (checking/project-state opts nss))
        per-ns-failures (get-in opts [:project-state :per-ns-failures])
        checkable-nss (apply dissoc nss (keys per-ns-failures))
        {:keys [run-start discovery-warn ns-start finding ns-end run-end form-debug]}
        (output/printer opts)
        totals (atom {:finding-count 0
                      :exception-count 0
                      :namespace-count (count nss)
                      :namespaces-with-findings 0
                      :per-namespace-counts {}})]
    (run-start opts nss)
    (doseq [failure failures]
      (discovery-warn (failure->info failure)))
    (doseq [[ns-sym {:keys [source-file ^Throwable exception phase]}] per-ns-failures]
      (discovery-warn {:path (str source-file)
                       :message (str "Skeptic skipped namespace " ns-sym
                                     " (phase " (name phase) "): "
                                     (.getName (class exception))
                                     ": "
                                     (or (.getMessage exception) (str exception)))}))
    (let [errored (atom false)]
      (doseq [[ns source-file] checkable-nss]
        (ns-start ns source-file opts)
        (let [ns-findings (atom 0)
              {:keys [results]} (checking/check-namespace opts ns source-file)
              opts* (assoc opts :explain-full (boolean (:explain-full opts)))]
          (doseq [result results]
            (if (= :debug-form (:report-kind result))
              (form-debug ns result opts)
              (let [summary (inrep/report-summary result opts*)
                    exception? (= :exception (:report-kind summary))]
                (finding ns result summary opts*)
                (reset! errored true)
                (swap! ns-findings inc)
                (swap! totals update
                       (if exception? :exception-count :finding-count)
                       inc))))
          (swap! totals assoc-in [:per-namespace-counts ns] @ns-findings)
          (when (pos? @ns-findings)
            (swap! totals update :namespaces-with-findings inc))
          (ns-end ns @ns-findings opts*)))
      (run-end @errored @totals opts)
      (if @errored 1 0))))
