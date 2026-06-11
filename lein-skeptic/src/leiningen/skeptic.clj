(ns leiningen.skeptic
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.core.classpath]
            [leiningen.core.eval]
            [leiningen.core.main]
            [schema.core]
            [skeptic.cli.cljs.lein :as cljs-lein]
            [skeptic.cli.options :as cli-opts]
            [skeptic.worker.classpath :as worker-classpath]
            [skeptic.worker.client :as worker-client])
  (:import [java.util.concurrent TimeUnit]))

(defn- required-var
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "Could not resolve " sym) {:sym sym}))))

(defn- vlog
  "Emit a `-v`-gated startup marker to stderr (so it appears even when stdout
   is redirected via -o). Each marker names the host-side step about to run.
   A user reporting a hang can name the last marker printed; that identifies
   which synchronous call blocked."
  [verbose? label]
  (when verbose?
    (binding [*out* *err*]
      (println (str "[skeptic startup] " label))
      (flush))))

(defn- resolve-worker-jars
  "Resolve Skeptic's worker dependency declaration via leiningen's aether
   wrapper. The declaration lives in `skeptic.worker.deps/worker-deps` —
   Skeptic owns it as data, lein resolves it as a synthetic project.
   The synthetic project inherits the real project's `:repositories` so
   aether knows where to download from."
  [project verbose?]
  (let [worker-deps (deref (required-var 'skeptic.worker.deps/worker-deps))
        synthetic-project {:dependencies worker-deps
                           :repositories (:repositories project)}]
    (vlog verbose? (str "resolving worker deps ("
                        (count worker-deps) " coords) against "
                        (count (:repositories project)) " repositories: "
                        (mapv first (:repositories project))))
    (mapv #(.getAbsolutePath ^java.io.File %)
          (leiningen.core.classpath/resolve-managed-dependencies
            :dependencies :managed-dependencies synthetic-project))))

(defn- resolve-plugin-jars
  "lein's `get-classpath` only walks `:dependencies`; plugin jars resolved
   from `:plugins` are on lein's own process classpath but never reach the
   project's runtime classpath. Some projects declare cljs-side libraries
   under `:plugins` (e.g. `lein-doo` for `doo.runner` test entrypoints);
   without this augmentation those namespaces are unresolvable when Skeptic
   analyzes the project's cljs sources."
  [project verbose?]
  (vlog verbose? (str "resolving :plugins as dependencies ("
                      (count (:plugins project)) " plugins): "
                      (mapv first (:plugins project))))
  (mapv #(.getAbsolutePath ^java.io.File %)
        (leiningen.core.classpath/resolve-managed-dependencies
          :plugins :managed-dependencies project)))

(defn- worker-project
  "The project as launched for the worker JVM. Discovered cljs source-paths
   (e.g. cljsbuild :builds source-paths) are added so lein's classpath
   construction and prep include them; the cljs analyzer's resolver
   (`cljs.analyzer/locate-src` → `cljs.util/ns->source` → `io/resource`)
   needs them on the worker classpath to find sibling required namespaces.
   The project's `:dependencies` are never touched: Skeptic's worker runtime
   is resolved separately and appended to the launch classpath, so dependency
   mediation never blends Skeptic's graph with the project's."
  [project paths]
  (update project :source-paths #(vec (distinct (concat % paths)))))

(defn- project-worker-form
  [project port-file]
  (let [port-path (.getPath ^java.io.File port-file)]
    `(do
       (set! ~'*warn-on-reflection* ~(:warn-on-reflection project))
       ~@(map (fn [[k v]] `(set! ~k ~v)) (:global-vars project))
       (require 'skeptic.worker.server)
       ~@(:injections project)
       (skeptic.worker.server/run-worker!
        (fn [port#] (spit ~port-path (str port#)))))))

(def ^:private bootclasspath-prefix "-Xbootclasspath/a:")

(defn- augment-classpath-arg
  [prev arg combine]
  (cond
    (= "-classpath" prev) (combine arg)
    (str/starts-with? arg bootclasspath-prefix)
    (str bootclasspath-prefix (combine (subs arg (count bootclasspath-prefix))))
    :else arg))

(defn- worker-launch-command
  "Lein's project JVM command for `form`, with Skeptic's worker runtime
   appended to the classpath argument. Lein's own classpath (project entries)
   stays first; `worker-classpath-entries` appends resolved `:plugins` jars,
   the separately resolved worker jars, and Skeptic's worker source entry.
   The project's pinned versions win on every shared lib via first-occurrence,
   and Skeptic's coordinates never enter the project's dependency graph."
  [project form worker-jars plugin-jars]
  (let [command (mapv str (leiningen.core.eval/shell-command project form))
        separator (java.util.regex.Pattern/quote java.io.File/pathSeparator)
        combine (fn [cp]
                  (:combined (worker-classpath/worker-classpath-entries
                              worker-jars
                              (concat (str/split cp (re-pattern separator))
                                      plugin-jars))))]
    (mapv #(augment-classpath-arg %1 %2 combine) (cons nil command) command)))

(defn- configure-process-builder!
  [^ProcessBuilder pb project]
  (.directory pb (io/file (:root project)))
  (let [env (.environment pb)
        overrides leiningen.core.eval/*env*]
    (when (:replace (meta overrides))
      (.clear env))
    (.remove env "DRIP_INIT")
    (.remove env "DRIP_INIT_CLASS")
    (doseq [[k v] overrides]
      (let [k (if (keyword? k) (name k) (str k))]
        (if (nil? v)
          (.remove env k)
          (.put env k (str v))))))
  pb)

(defn- remember-line
  [lines line]
  (swap! lines
         (fn [current]
           (let [next-lines (conj current line)]
             (if (> (count next-lines) 200)
               (subvec next-lines (- (count next-lines) 200))
               next-lines)))))

(defn- start-output-drain!
  [stream label verbose? lines]
  (let [thread
        (Thread.
         ^Runnable
         (fn []
           (try
             (with-open [reader (io/reader stream)]
               (doseq [line (line-seq reader)]
                 (remember-line lines line)
                 (when verbose?
                   (.println System/err (str "[skeptic worker " label "] " line))
                   (.flush System/err))))
             (catch Throwable _))))]
    (.setDaemon thread true)
    (.setName thread (str "skeptic-project-worker-" label "-drain"))
    (.start thread)
    thread))

(defn- remove-shutdown-hook!
  [hook]
  (when hook
    (try
      (.removeShutdownHook (Runtime/getRuntime) ^Thread hook)
      (catch IllegalStateException _)
      (catch Throwable _))))

(defn- join-output-drains!
  [{:keys [stdout-drain stderr-drain]}]
  (doseq [^Thread thread [stdout-drain stderr-drain]]
    (when thread
      (try
        (.join thread 1000)
        (catch InterruptedException _
          (.interrupt (Thread/currentThread)))))))

(defn- process-output
  [{:keys [stdout-lines stderr-lines]}]
  {:worker-stdout @stdout-lines
   :worker-stderr @stderr-lines})

(defn- process-output-message
  [{:keys [worker-stdout worker-stderr]}]
  (let [sections (cond-> []
                   (seq worker-stdout)
                   (conj (str "Worker stdout:\n" (str/join "\n" worker-stdout)))
                   (seq worker-stderr)
                   (conj (str "Worker stderr:\n" (str/join "\n" worker-stderr))))]
    (when (seq sections)
      (str "\n" (str/join "\n" sections)))))

(defn- terminate-project-process!
  [{:keys [^Process proc shutdown-hook] :as process-state}]
  (when (.isAlive proc)
    (.destroy proc)
    (when-not (.waitFor proc 1000 TimeUnit/MILLISECONDS)
      (.destroyForcibly proc)
      (.waitFor proc 1000 TimeUnit/MILLISECONDS)))
  (join-output-drains! process-state)
  (remove-shutdown-hook! shutdown-hook))

(defn- owned-process-state
  [^Process proc verbose?]
  (let [stdout-lines (atom [])
        stderr-lines (atom [])
        shutdown-hook (Thread. ^Runnable
                               (fn []
                                 (when (.isAlive proc)
                                   (.destroy proc))))]
    (.setName shutdown-hook "skeptic-project-worker-shutdown")
    (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
    {:proc proc
     :stdout-lines stdout-lines
     :stderr-lines stderr-lines
     :stdout-drain (start-output-drain! (.getInputStream proc)
                                        "stdout" verbose? stdout-lines)
     :stderr-drain (start-output-drain! (.getErrorStream proc)
                                        "stderr" verbose? stderr-lines)
     :shutdown-hook shutdown-hook}))

(defn- start-project-process!
  [project port-file verbose?]
  (binding [*out* *err*]
    (leiningen.core.eval/prep project))
  (when (:warn-on-reflection project)
    (binding [*out* *err*]
      (leiningen.core.main/info "Reflection warning, lein-skeptic worker.")))
  (let [worker-jars (resolve-worker-jars project verbose?)
        plugin-jars (resolve-plugin-jars project verbose?)
        form (project-worker-form project port-file)
        command (worker-launch-command project form worker-jars plugin-jars)
        pb (configure-process-builder! (ProcessBuilder. ^java.util.List command)
                                       project)]
    (owned-process-state (.start pb) verbose?)))

(defn- wait-for-worker-port
  [port-file {:keys [^Process proc] :as process-state}]
  (loop []
    (let [contents (when (.exists ^java.io.File port-file)
                     (str/trim (slurp port-file)))]
      (cond
        (seq contents) (Integer/parseInt contents)
        (not (.isAlive proc))
        (do
          (join-output-drains! process-state)
          (let [output (process-output process-state)]
            (throw (ex-info
                    (str "project-runtime worker failed before port handshake"
                         (process-output-message output))
                    (merge {:exit-code (.exitValue proc)
                            :port-file (.getPath ^java.io.File port-file)}
                           output)))))
        :else
        (do
          (Thread/sleep 10)
          (recur))))))

(defn- request-worker-shutdown!
  "Ask the worker to stop via the shutdown op. Returns true when the worker
   acknowledged the request, false when it could not be delivered."
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
            (str "worker shutdown request failed; terminating owned process: "
                 (.getName (class e)) ": " (.getMessage e)))
      false)))

(defn- stop-project-worker!
  [port-file process-state port verbose?]
  (try
    (when (request-worker-shutdown! port verbose?)
      (let [^Process proc (:proc process-state)]
        (when (.isAlive proc)
          (.waitFor proc 10000 TimeUnit/MILLISECONDS))))
    (finally
      (terminate-project-process! process-state)
      (.delete ^java.io.File port-file))))

(defn- spawn-project-worker!
  [project paths verbose?]
  (let [port-file (java.io.File/createTempFile "skeptic-project-worker-" ".port")
        _ (.delete port-file)
        launch-project (worker-project project paths)]
    (try
      (let [process-state (start-project-process! launch-project port-file verbose?)]
        (try
          (let [port (wait-for-worker-port port-file process-state)]
            (vlog verbose? (str "project-runtime worker handshake received port=" port))
            (assoc process-state
                   :port port
                   :stop-fn #(stop-project-worker!
                              port-file process-state port verbose?)))
          (catch Throwable e
            (try
              (terminate-project-process! process-state)
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
      (let [verbose? (:verbose options)
            _ (vlog verbose? "discovering cljs sources")
            paths (:source-paths (cljs-lein/discover-sources project))
            _ (vlog verbose? "loading skeptic.profiling / skeptic.core")
            profiling-run (required-var 'skeptic.profiling/run)
            check-project (required-var 'skeptic.core/check-project)
            _ (vlog verbose? "entering check-project")
            output-path (:output options)
            writer (when output-path (io/writer output-path))]
        (try
          (binding [*out* (or writer *out*)]
            (schema.core/without-fn-validation
              (profiling-run options (str (:root project) "/target")
                (fn []
                  (apply check-project
                         (assoc options
                                :worker-spawn
                                (fn [worker-verbose?]
                                  (spawn-project-worker! project paths worker-verbose?)))
                         (:root project)
                         paths)))))
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
