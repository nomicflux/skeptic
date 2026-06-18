(ns skeptic.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [schema.core :as s]
            [skeptic.analysis.class-oracle :as class-oracle]
            [skeptic.checking.opts :as copts]
            [skeptic.checking.pipeline :as checking]
            [skeptic.config :as config]
            [skeptic.file :as file]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.output :as output]
            [skeptic.worker.client :as wc]
            [skeptic.worker.process :as wproc]))

(defn- discover-project-files
  [opts _root paths]
  (if (contains? opts :skeptic/source-files)
    {:files (vec (:skeptic/source-files opts))
     :failures (vec (:skeptic/source-discovery-failures opts))}
    (reduce (fn [{:keys [files failures]} path]
              (let [resolved (io/file path)]
                (if-not (.exists resolved)
                  {:files files :failures failures}
                  (let [{new-files :files
                         new-failures :failures}
                        (file/discover-clojure-files (.getPath resolved))]
                    {:files (into files new-files)
                     :failures (into failures new-failures)}))))
            {:files []
             :failures []}
            paths)))

(defn- blocking-discovery-failures
  "A failure entry is BLOCKING only when it indicates Skeptic could not
  read a source path at all (carries :exception). Eligibility rejections
  (carry :unresolvable-deps) are informational warnings — they have
  already removed the file from the worker's input set; throwing here
  would defeat the warning channel and the ns-discovery-warning JSONL
  line."
  [requested-namespaces discovered-pairs failures]
  (let [hard-failures (filter :exception failures)]
    (cond
      (empty? hard-failures) []
      (seq requested-namespaces) (if (every? (set (map first discovered-pairs))
                                              requested-namespaces)
                                   []
                                   hard-failures)
      :else hard-failures)))

(defn- failure->info
  [{:keys [path exception unresolvable-deps message]}]
  (cond
    exception
    {:path path
     :message (or (.getMessage ^Exception exception)
                  (str exception))}
    unresolvable-deps
    {:path path
     :message message
     :unresolvable-deps unresolvable-deps}
    :else
    {:path path
     :message (or message "")}))

(defn- expand-namespace-args
  [raw]
  (->> raw
       (mapcat #(str/split % #","))
       (map str/trim)
       (remove str/blank?)
       (mapv symbol)))

(defn- run-with-cleanup
  [work cleanup]
  (let [outcome (try
                  {:value (work)}
                  (catch Throwable e
                    {:error e}))]
    (if-let [primary (:error outcome)]
      (do
        (try
          (cleanup)
          (catch Throwable cleanup-error
            (.addSuppressed primary cleanup-error)))
        (throw primary))
      (do
        (cleanup)
        (:value outcome)))))

(defn- with-worker-connection
  [verbose? worker startup-log work]
  (run-with-cleanup
   (fn []
     (let [conn (wc/connect verbose? (:port worker))]
       (startup-log "worker connection established; interning host classes")
       (run-with-cleanup
        #(work conn)
        #(wc/disconnect! conn))))
   #(wproc/stop! worker)))

(s/defn check-project :- s/Int
  [opts :- copts/CheckProjectOpts root :- (s/cond-pre s/Str java.io.File) & paths :- [s/Str]]
  (let [raw-namespaces (:namespace opts)
        requested-namespaces (when (seq raw-namespaces)
                               (expand-namespace-args raw-namespaces))
        raw-config (config/load-raw-config root)
        type-overrides (config/compile-overrides (:type-overrides raw-config))
        opts (assoc opts :skeptic/config raw-config :skeptic/type-overrides type-overrides)
        {:keys [files failures]} (discover-project-files opts root paths)
        files (remove #(config/path-excluded? root (:exclude-files raw-config) %) files)
        files (if (:cljs-disable opts)
                (remove #(str/ends-with? (.getName ^java.io.File %) ".cljs") files)
                files)
        ;; Pairs, never a map: one namespace may be declared by several files
        ;; (.clj alongside .cljc). Which file defines it is the project's own
        ;; load's decision, made in the worker — never a host-side collapse.
        discovered-pairs (try (->> files
                                   (map file/ns-for-clojure-file)
                                   (remove (comp nil? first))
                                   (into []))
                              (catch Exception e
                                (println "Couldn't get namespaces: " e)
                                (throw e)))
        blocking-failures (blocking-discovery-failures requested-namespaces
                                                       discovered-pairs
                                                       failures)
        _ (when (seq blocking-failures)
            (doseq [failure blocking-failures]
              (println "Skeptic could not read source path:"
                       (format "%s -- %s"
                               (:path failure)
                               (or (some-> ^Exception (:exception failure) .getMessage)
                                   (str (:exception failure))))))
            (println "Skeptic checks paths from your project's :source-paths"
                     "and :test-paths (or --paths on the deps.edn entrypoint).")
            (println "If a path above does not exist, declare the right paths"
                     "in project.clj / deps.edn or pass --paths explicitly.")
            (throw (ex-info "Skeptic could not read one or more source paths."
                            {:failures blocking-failures})))
        verbose? (boolean (:verbose opts))
        startup-log (fn [label]
                      (when verbose?
                        (binding [*out* *err*]
                          (println (str "[skeptic startup] " label))
                          (flush))))
        worker-spawn (:worker-spawn opts)
        combined-cp (:combined (:worker-classpath opts))
        _ (when-not (or worker-spawn combined-cp)
            (throw (ex-info "check-project requires :worker-spawn or :worker-classpath"
                            {:opts (keys opts)})))
        _ (if worker-spawn
            (startup-log "spawning worker through project runtime")
            (startup-log (str "spawning worker JVM (cp length="
                              (count combined-cp) " chars, entries="
                              (inc (count (re-seq (re-pattern
                                                    (java.util.regex.Pattern/quote
                                                      (System/getProperty "path.separator")))
                                                  combined-cp)))
                              ")")))
        worker (if worker-spawn
                 (worker-spawn verbose?)
                 (wproc/spawn! (or combined-cp
                                   (throw (ex-info "missing worker classpath"
                                                   {:opts (keys opts)})))
                               (str root)
                               verbose?))]
    (startup-log (str "worker handshake received port=" (:port worker)
                      "; connecting"))
    (with-worker-connection
      verbose? worker startup-log
      (fn [conn]
      (let [host-handles (class-oracle/intern-host-classes! conn)
            _ (startup-log (str "host classes interned (" (count host-handles) " classes); binding worker context"))]
        (binding [class-oracle/*worker-conn* conn
                  class-oracle/*host-class-handles* host-handles
                  class-oracle/*class-rel-cache* (atom {})
                  class-oracle/*predicate-cache* (atom {})]
          (startup-log "building project-state (discovers + analyzes every namespace via worker)")
          (let [project-state (checking/project-state (assoc opts :worker-conn conn) discovered-pairs)
                _ (startup-log (str "project-state built (" (count discovered-pairs) " files discovered)"))
                requested (if (seq requested-namespaces)
                            (filterv (comp (set requested-namespaces) first) discovered-pairs)
                            discovered-pairs)
                shadowed-files (or (:shadowed-files project-state) {})
                shadowed (filterv (fn [[_ sf]] (contains? shadowed-files sf)) requested)
                nss-to-check (vec (remove (fn [[_ sf]] (contains? shadowed-files sf)) requested))
                printer-opts (select-keys opts [:verbose :debug :analyzer :explain-full :show-context])
                report-opts {:explain-full (boolean (:explain-full opts))}
                form-opts opts
                {:keys [run-start discovery-warn ns-start finding ns-end run-end form-debug]}
                (output/printer opts)
                totals (atom {:finding-count 0
                              :exception-count 0
                              :analysis-skipped-count 0
                              :namespace-count (count nss-to-check)
                              :namespaces-with-findings 0
                              :per-namespace-counts {}})]
            (run-start printer-opts nss-to-check)
            (doseq [failure failures]
              (discovery-warn printer-opts (failure->info failure)))
            (doseq [[ns-sym sf] shadowed]
              (discovery-warn printer-opts
                              {:path (str sf)
                               :message (str "namespace " ns-sym " is defined by "
                                             (str/join ", " (:defined-by (get shadowed-files sf)))
                                             " on the project's classpath; the project never"
                                             " loads this file and it was not checked")}))
            (let [errored (atom false)]
              (doseq [[ns source-file] nss-to-check]
                (ns-start ns source-file printer-opts)
                (let [ns-findings (atom 0)
                      {:keys [results]} (checking/check-namespace project-state ns source-file form-opts)]
                  (doseq [result results]
                    (if (= :debug-form (:report-kind result))
                      (form-debug ns result printer-opts)
                      (let [summary (inrep/report-summary result report-opts)
                            kind (:report-kind summary)
                            counter-key (case kind
                                          :exception        :exception-count
                                          :analysis-skipped :analysis-skipped-count
                                          :finding-count)]
                        (finding ns result summary printer-opts)
                        (when-not (= :analysis-skipped kind)
                          (reset! errored true)
                          (swap! ns-findings inc))
                        (swap! totals update counter-key inc))))
                  (swap! totals assoc-in [:per-namespace-counts ns] @ns-findings)
                  (when (pos? @ns-findings)
                    (swap! totals update :namespaces-with-findings inc))
                  (ns-end ns @ns-findings printer-opts)))
              (run-end @errored @totals printer-opts)
              (if @errored 1 0)))))))))
