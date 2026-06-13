(ns leiningen.skeptic
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.core.eval]
            [leiningen.core.main]
            [schema.core]
            [skeptic.cli.options :as cli-opts]
            [skeptic.worker.client :as worker-client]))

(defn- required-var
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "Could not resolve " sym) {:sym sym}))))

(defn- vlog
  [verbose? label]
  (when verbose?
    (binding [*out* *err*]
      (println (str "[skeptic startup] " label))
      (flush))))

(defn- project-with-worker-deps
  "Augment the project's :dependencies with Skeptic's worker runtime coords so
   Lein's own resolver builds the worker classpath. The user's :dependencies
   are not replaced — Lein merges via standard dependency resolution; the
   project's pinned versions for shared libs win via Aether's nearest-direct
   rule. This is the only project-map mutation Skeptic performs; profile
   composition, classpath construction, prep, injections, and global-vars are
   all Lein's via eval-in-project."
  [project]
  (let [worker-deps (deref (required-var 'skeptic.worker.deps/worker-deps))]
    (update project :dependencies (fnil into []) worker-deps)))

(defn- worker-form
  "The form eval-in-project evaluates in the project JVM. eval-in-project
   itself wraps this with (set! *warn-on-reflection*), :global-vars, and
   :injections — Skeptic does not duplicate that wrapping."
  [port-file]
  (let [port-path (.getPath ^java.io.File port-file)]
    `(do
       (require 'skeptic.worker.server)
       (skeptic.worker.server/run-worker!
        (fn [port#] (spit ~port-path (str port#)))))))

(defn- start-worker-thread!
  "eval-in-project blocks until the form returns, but Skeptic's worker form
   is a runloop. Run it on a dedicated thread so the plugin's main thread can
   drive checking through the worker's nREPL port. The thread carries the
   eval-in-project's outcome (normal return or Throwable) for inspection
   after shutdown."
  [project form verbose?]
  (let [outcome (atom nil)
        thread
        (Thread.
         ^Runnable
         (fn []
           (try
             (vlog verbose? "eval-in-project: invoking worker form")
             (leiningen.core.eval/eval-in-project project form)
             (reset! outcome {:value :returned})
             (catch Throwable e
               (reset! outcome {:error e})))))]
    (.setDaemon thread true)
    (.setName thread "skeptic-worker-eval-in-project")
    (.start thread)
    {:thread thread :outcome outcome}))

(defn- wait-for-worker-port
  [port-file {:keys [outcome]}]
  (loop []
    (let [contents (when (.exists ^java.io.File port-file)
                     (str/trim (slurp port-file)))]
      (cond
        (seq contents) (Integer/parseInt contents)

        @outcome
        (let [{:keys [error]} @outcome]
          (throw (ex-info
                  (if error
                    (str "worker eval-in-project failed before port handshake: "
                         (.getName (class error)) ": " (.getMessage error))
                    "worker eval-in-project returned before port handshake")
                  {:port-file (.getPath ^java.io.File port-file)
                   :outcome @outcome}
                  (when error error))))

        :else
        (do
          (Thread/sleep 10)
          (recur))))))

(defn- request-worker-shutdown!
  "Ask the worker to stop via the shutdown op. The worker's run-worker!
   loop exits cleanly on receipt; eval-in-project's thread then returns
   normally."
  [port verbose?]
  (try
    (let [conn (worker-client/connect verbose? port)]
      (try
        (worker-client/ask conn {:op "shutdown"})
        true
        (finally
          (worker-client/disconnect! conn))))
    (catch Throwable e
      (vlog verbose?
            (str "worker shutdown request failed: "
                 (.getName (class e)) ": " (.getMessage e)))
      false)))

(defn- stop-project-worker!
  [port-file {:keys [^Thread thread]} port verbose?]
  (try
    (when (request-worker-shutdown! port verbose?)
      (.join thread 10000))
    (finally
      (when (.isAlive thread)
        (.interrupt thread)
        (.join thread 1000))
      (.delete ^java.io.File port-file))))

(defn- spawn-project-worker!
  "Launch the worker JVM via Lein's eval-in-project on a dedicated thread.
   Lein owns: classpath, profile composition, prep, injections, global-vars,
   jvm-opts. Skeptic owns: the request that the worker form runs, with the
   worker runtime coords added to :dependencies via project-with-worker-deps."
  [project verbose?]
  (let [port-file (java.io.File/createTempFile "skeptic-project-worker-" ".port")
        _ (.delete port-file)
        launch-project (project-with-worker-deps project)
        form (worker-form port-file)]
    (try
      (let [eval-state (start-worker-thread! launch-project form verbose?)]
        (try
          (let [port (wait-for-worker-port port-file eval-state)]
            (vlog verbose? (str "worker handshake received port=" port))
            (assoc eval-state
                   :port port
                   :stop-fn #(stop-project-worker!
                              port-file eval-state port verbose?)))
          (catch Throwable e
            (try
              (when (.isAlive ^Thread (:thread eval-state))
                (.interrupt ^Thread (:thread eval-state)))
              (catch Throwable cleanup-error
                (.addSuppressed e cleanup-error)))
            (throw e))))
      (finally
        (when (.exists port-file)
          (.delete port-file))))))

(defn- run-skeptic
  [project args]
  (let [{:keys [options summary errors]} (cli-opts/parse args)]
    (cond
      (:help options) (println summary)
      errors          (do (doseq [e errors] (leiningen.core.main/warn e))
                          (leiningen.core.main/warn summary)
                          (leiningen.core.main/abort))
      :else
      (let [check-project (required-var 'skeptic.core/check-project)
            profiling-run (required-var 'skeptic.profiling/run)
            output-path (:output options)
            writer (when output-path (io/writer output-path))
            discovery-paths (vec (concat (:source-paths project)
                                         (:test-paths project)))]
        (try
          (binding [*out* (or writer *out*)]
            (schema.core/without-fn-validation
              (profiling-run options (str (:root project) "/target")
                (fn []
                  (check-project
                   (assoc options
                          :worker-spawn
                          (fn [worker-verbose?]
                            (spawn-project-worker! project worker-verbose?))
                          :skeptic/discovery-paths discovery-paths)
                   (:root project))))))
          (finally
            (when writer
              (.flush writer)
              (.close writer))))))))

(defn skeptic
  {:doc (str "Run skeptic on this project's source- and test-paths.\n\n"
             "Usage: lein skeptic [OPTIONS]\n\n"
             "Options:\n"
             (:summary (cli-opts/parse [])))}
  [project & args]
  (when-some [exit-code (run-skeptic project args)]
    (System/exit exit-code)))
