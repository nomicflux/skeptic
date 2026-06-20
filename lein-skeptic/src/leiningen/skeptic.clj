(ns leiningen.skeptic
  "Hermetic-host launcher for Skeptic on lein.

   The launcher's job, per docs/current-plans/hermetic-host-launcher.md:

   1. Resolve Skeptic host-deps via aether against a synthetic project
      (invisible to lein's plugin-tree mediation / :pedantic? :abort).
   2. Resolve Skeptic worker-deps the same way.
   3. Prep the lein project (:prep-tasks etc.).
   4. Spawn the worker JVM using leiningen.core.eval/shell-command, with
      -classpath augmented to (project-cp ++ plugin-jars ++ worker-jars
      ++ skeptic-worker-self-entry). Wait for port handshake.
   5. Spawn the host JVM with host-jars as -cp. Pipe an EDN payload
      describing {root, paths, worker, options} to its stdin.
   6. Stream host stdout/stderr to lein stdout/stderr. Wait for host exit.
   7. Send worker shutdown op; terminate cleanly.

   The launcher namespace has ZERO :require entries from skeptic.* — every
   Skeptic-side function used at launcher time is either inlined here or
   read out of the Skeptic jar as text (see read-skeptic-vector)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.core.classpath]
            [leiningen.core.eval]
            [leiningen.core.main])
  (:import [java.util.concurrent TimeUnit]
           [java.util.jar JarFile]))

;; -- Skeptic library coordinate the launcher resolves at task time ---

(def ^:const skeptic-version
  "Skeptic library version this lein-skeptic ships with. Kept in sync
   with the version declared in skeptic/project.clj and lein-skeptic/
   project.clj via script/verify-monorepo-versions.sh."
  "0.9.0")

;; -- Verbose startup markers ------------------------------------------

(defn- vlog
  [verbose? label]
  (when verbose?
    (binding [*out* *err*]
      (println (str "[skeptic startup] " label))
      (flush))))

;; -- Reading Skeptic's deps vectors out of its jar -------------------

(defn- jar-resource-text
  "Read a resource path out of `jar-path` as a String. Returns nil if
   the entry is absent."
  [^String jar-path ^String resource-path]
  (with-open [jar (JarFile. jar-path)]
    (when-let [entry (.getJarEntry jar resource-path)]
      (slurp (.getInputStream jar entry)))))

(defn- read-skeptic-vector
  "Extract a (def VAR-NAME 'LITERAL-VECTOR) form's value from a clj file
   inside a jar, without requiring the namespace. Selects the def by
   symbol-equality on its second element. The def must be a literal
   quoted vector — no runtime evaluation."
  [^String jar-path ^String resource-path var-name]
  (let [src   (jar-resource-text jar-path resource-path)
        _     (when-not src
                (throw (ex-info (str "missing resource in skeptic jar: "
                                     resource-path)
                                {:jar jar-path :resource resource-path})))
        forms (read-string (str "[" src "]"))
        def-v (some (fn [f]
                      (when (and (seq? f)
                                 (= 'def (first f))
                                 (= var-name (second f)))
                        ;; Form is (def NAME doc? value). Take the last
                        ;; element; for (def NAME 'V) the value is 'V.
                        (let [val (last f)]
                          (if (and (seq? val) (= 'quote (first val)))
                            (second val)
                            val))))
                    forms)]
    (when-not def-v
      (throw (ex-info (str "could not find (def " var-name ") in "
                           resource-path)
                      {:jar jar-path :resource resource-path
                       :var var-name})))
    def-v))

;; -- Aether synthetic-project resolution ------------------------------

(defn- synthetic-project
  "Build the synthetic project map for aether resolution. Inherits
   repo-connection keys (per lein core/project.clj:998) so private
   repos and mirrors propagate; does NOT inherit the user's
   :dependencies, :plugins, or :managed-dependencies."
  [project deps]
  (cond-> {:dependencies deps}
    (:repositories project)     (assoc :repositories (:repositories project))
    (:mirrors project)          (assoc :mirrors (:mirrors project))
    (:certificates project)     (assoc :certificates (:certificates project))
    (:local-repo project)       (assoc :local-repo (:local-repo project))))

(defn- resolve-deps
  "Aether resolution of `deps` against the user project's repository
   connection settings. Returns absolute jar paths. Never adds resolved
   jars to lein's classloader (does NOT pass :add-classpath? true)."
  [project deps]
  (mapv #(.getAbsolutePath ^java.io.File %)
        (leiningen.core.classpath/resolve-managed-dependencies
          :dependencies
          :managed-dependencies
          (synthetic-project project deps))))

(defn- resolve-host-jars
  "Resolve the host JVM classpath: Skeptic + tools.reader pin + Clojure
   pin. Reads host-deps from the Skeptic jar itself (after first
   resolving just the skeptic coord to locate it)."
  [project skeptic-jar verbose?]
  (vlog verbose? "resolving host deps from skeptic.host.deps/host-deps")
  (let [host-deps (read-skeptic-vector skeptic-jar
                                        "skeptic/host/deps.clj"
                                        'host-deps)]
    (resolve-deps project host-deps)))

(defn- resolve-worker-jars
  "Resolve the worker JVM classpath: clojure + analyzer + tools.reader +
   nrepl + transit ... per skeptic.worker.deps/worker-deps inside the
   Skeptic jar."
  [project skeptic-jar verbose?]
  (vlog verbose? "resolving worker deps from skeptic.worker.deps/worker-deps")
  (let [worker-deps (read-skeptic-vector skeptic-jar
                                          "skeptic/worker/deps.clj"
                                          'worker-deps)]
    (resolve-deps project worker-deps)))

(defn- locate-skeptic-jar
  "First aether call: resolve just `[skeptic skeptic-version]` to find
   the jar's path. Used to extract the embedded host-deps and worker-deps
   vectors without putting Skeptic on lein's classloader."
  [project verbose?]
  (vlog verbose? (str "locating skeptic-" skeptic-version " jar"))
  (let [jars (resolve-deps project
                            [['org.clojars.nomicflux/skeptic skeptic-version]])]
    (or (first (filter #(re-find (re-pattern (str "/skeptic-" skeptic-version "[.-]"))
                                  %)
                       jars))
        (throw (ex-info (str "could not locate skeptic-" skeptic-version
                             " jar in aether resolution")
                        {:resolved jars})))))

(defn- resolve-plugin-jars
  "Resolve the project's :plugins as a flat jar list. Some projects
   declare cljs-side libraries under :plugins (e.g. lein-doo for
   doo.runner test entrypoints); those are still needed on the worker
   classpath for the cljs analyzer."
  [project verbose?]
  (vlog verbose? (str "resolving :plugins as dependencies ("
                      (count (:plugins project)) " plugins): "
                      (mapv first (:plugins project))))
  (mapv #(.getAbsolutePath ^java.io.File %)
        (leiningen.core.classpath/resolve-managed-dependencies
          :plugins :managed-dependencies project)))

;; -- Worker classpath assembly (inlined from skeptic.worker.classpath) --
;; Inlined to avoid requiring skeptic.* from the launcher namespace.
;; Equivalence with skeptic.worker.classpath/worker-classpath-entries
;; is asserted by a test (lein-skeptic test suite).

(def ^:private path-separator
  (System/getProperty "path.separator"))

(defn- skeptic-worker-self-entry
  "The path that, when added to the worker JVM's -cp, makes
   skeptic.worker.server requirable. We can't use io/resource (Skeptic
   isn't on the launcher's CL), so we use the resolved Skeptic jar path
   directly."
  [skeptic-jar]
  skeptic-jar)

(defn- worker-cp-string
  "Build the worker JVM's combined -cp string:
   (concat project-cp worker-jars [skeptic-self]), de-duped first-
   occurrence, path-separator joined.
   Project entries first → project's pinned Clojure/libs win on
   getResource calls."
  [worker-jars project-cp skeptic-jar]
  (let [worker      (vec (map str (or worker-jars [])))
        project     (vec (distinct (map str (or project-cp []))))
        self-entry  (skeptic-worker-self-entry skeptic-jar)
        combined    (vec (distinct (concat project worker [self-entry])))]
    (str/join path-separator combined)))

;; -- Worker form & launch command -------------------------------------

(defn- worker-project
  "The project as launched for the worker JVM. Discovered cljs source-
   paths are added so lein's classpath construction includes them."
  [project paths]
  (update project :source-paths #(vec (distinct (concat % paths)))))

(defn- project-worker-form
  "The init form Skeptic's worker JVM evaluates on startup. Matches
   lein's eval-in-project ordering: warn-on-reflection, global-vars,
   require, injections, run-worker!"
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
  "Replace the worker command's -classpath value (or -Xbootclasspath/a:
   value) with our combined cp string. Other flags (-javaagent, -D...,
   etc.) pass through unchanged."
  [prev arg combine]
  (cond
    (= "-classpath" prev) (combine arg)
    (str/starts-with? arg bootclasspath-prefix)
    (str bootclasspath-prefix (combine (subs arg (count bootclasspath-prefix))))
    :else arg))

(defn- worker-launch-command
  "Lein's project JVM command for `form`, with -classpath replaced by
   project-cp ++ plugin-jars ++ worker-jars ++ skeptic-self. Every other
   element of the command (java path, JVM args, -Dproperties, init
   form path) is exactly what lein would produce."
  [project form worker-jars plugin-jars skeptic-jar]
  (let [command (mapv str (leiningen.core.eval/shell-command project form))
        separator (java.util.regex.Pattern/quote java.io.File/pathSeparator)
        combine (fn [cp]
                  (worker-cp-string
                    worker-jars
                    (concat (str/split cp (re-pattern separator))
                            plugin-jars)
                    skeptic-jar))]
    (mapv #(augment-classpath-arg %1 %2 combine) (cons nil command) command)))

;; -- ProcessBuilder env setup, stream draining, shutdown hooks --------

(defn- configure-worker-process-builder!
  "Apply lein's :env overrides (from leiningen.core.eval/*env*) to the
   worker process's environment. Also strip DRIP_INIT* per lein's own
   convention."
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

(defn- configure-host-process-builder!
  "Host JVM env setup. Strip lein-specific env that the host should
   NOT inherit (LEIN_JVM_OPTS, DRIP_INIT, JVM_OPTS), then set cwd to
   the project root."
  [^ProcessBuilder pb project]
  (.directory pb (io/file (:root project)))
  (let [env (.environment pb)]
    (.remove env "LEIN_JVM_OPTS")
    (.remove env "DRIP_INIT")
    (.remove env "DRIP_INIT_CLASS")
    (.remove env "JVM_OPTS"))
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
  "Drain a worker's stdout/stderr stream into a bounded line buffer.
   Forward to lein's stderr under -v. EOF exits the thread cleanly."
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

(defn- start-passthrough-drain!
  "Drain the host JVM's stdout (or stderr) directly to lein's
   System/out (or System/err). The host JVM's stdout IS the user-facing
   output (porcelain JSONL or text); no buffering, no rewriting."
  [stream label ^java.io.PrintStream target]
  (let [thread
        (Thread.
         ^Runnable
         (fn []
           (try
             (with-open [reader (io/reader stream)]
               (doseq [line (line-seq reader)]
                 (.println target line)
                 (.flush target)))
             (catch Throwable _))))]
    (.setDaemon thread true)
    (.setName thread (str "skeptic-host-" label "-passthrough"))
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

;; -- Worker spawn (preserves all project fidelity) --------------------

(defn- start-project-process!
  [project port-file worker-jars plugin-jars skeptic-jar verbose?]
  (binding [*out* *err*]
    (leiningen.core.eval/prep project))
  (when (:warn-on-reflection project)
    (binding [*out* *err*]
      (leiningen.core.main/info "Reflection warning, lein-skeptic worker.")))
  (let [form (project-worker-form project port-file)
        command (worker-launch-command project form worker-jars plugin-jars skeptic-jar)
        pb (configure-worker-process-builder!
            (ProcessBuilder. ^java.util.List command)
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

(defn- stop-project-worker!
  [port-file process-state _port verbose?]
  (try
    (vlog verbose? "stopping worker via process destroy")
    (finally
      (terminate-project-process! process-state)
      (.delete ^java.io.File port-file))))

(defn- spawn-project-worker!
  [project paths worker-jars plugin-jars skeptic-jar verbose?]
  (let [port-file (java.io.File/createTempFile "skeptic-project-worker-" ".port")
        _ (.delete port-file)
        launch-project (worker-project project paths)]
    (try
      (let [process-state (start-project-process!
                           launch-project port-file
                           worker-jars plugin-jars skeptic-jar
                           verbose?)]
        (try
          (let [port (wait-for-worker-port port-file process-state)]
            (vlog verbose? (str "worker handshake received port=" port))
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

;; -- Host JVM spawn (Alt B: connects to already-running worker) -------

(def ^:private host-entry-form
  "Form evaluated by the host JVM at startup. Requires the host
   entrypoint and invokes it; the entrypoint reads its EDN payload
   from *in*.

   If `require` throws, propagate it (clojure.main -e will print the
   throwable). If the require succeeds but the entry var is missing,
   surface a diagnostic naming the loaded skeptic/cli/main.clj resource
   URL and the visible interns — that turns a silent NPE into evidence
   of which jar the host JVM actually loaded."
  '(do
     (require 'skeptic.cli.main)
     (let [entry (resolve 'skeptic.cli.main/check-from-launcher)]
       (if entry
         (System/exit (entry))
         (binding [*out* *err*]
           (println "skeptic host: skeptic.cli.main/check-from-launcher"
                    "did not resolve after require")
           (println "  loaded resource:"
                    (str (clojure.java.io/resource "skeptic/cli/main.clj")))
           (println "  ns-interns(skeptic.cli.main):"
                    (try (sort (keys (ns-interns 'skeptic.cli.main)))
                         (catch Throwable t (.getMessage t))))
           (println "  java.class.path entries:"
                    (count (clojure.string/split
                            (System/getProperty "java.class.path" "")
                            (re-pattern
                             (java.util.regex.Pattern/quote
                              (System/getProperty "path.separator"))))))
           (System/exit 3))))))

(defn- host-launch-command
  [host-jars]
  ["java" "-cp" (str/join path-separator host-jars)
   "clojure.main" "-e" (pr-str host-entry-form)])

(defn- discover-cljs-paths
  "Inlined minimal version of skeptic.cli.cljs.lein/discover-sources —
   returns just the source-paths vec. The host JVM does the actual
   .cljs/.cljc enumeration."
  [project]
  (let [builds (get-in project [:cljsbuild :builds])
        build-seq (if (map? builds) (vals builds) builds)
        cljsbuild-paths (->> build-seq
                             (mapcat :source-paths)
                             (remove nil?)
                             (mapv (fn [p]
                                     (let [f (io/file p)]
                                       (if (.isAbsolute f)
                                         (.getPath f)
                                         (.getPath (io/file (:root project) p)))))))]
    (vec (distinct (concat (:source-paths project)
                            (:test-paths project)
                            cljsbuild-paths)))))

(defn- run-host-jvm
  "Spawn the host JVM with host-jars as -cp, pipe the EDN payload to
   stdin, stream stdout/stderr to lein's. Returns the host process's
   exit code."
  [host-jars project paths worker-port args verbose?]
  (let [payload {:root   (:root project)
                 :paths  paths
                 :worker {:port worker-port}
                 :args   (vec args)}
        payload-edn (pr-str payload)
        command (host-launch-command host-jars)
        pb (configure-host-process-builder!
            (ProcessBuilder. ^java.util.List command) project)]
    (vlog verbose? (str "spawning host JVM (cp entries=" (count host-jars) ")"))
    (let [proc (.start pb)
          stdin (.getOutputStream proc)
          stdout-drain (start-passthrough-drain! (.getInputStream proc) "stdout" System/out)
          stderr-drain (start-passthrough-drain! (.getErrorStream proc) "stderr" System/err)
          shutdown-hook (Thread. ^Runnable
                                 (fn [] (when (.isAlive proc) (.destroy proc))))]
      (.setName shutdown-hook "skeptic-host-shutdown")
      (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
      (try
        ;; Write the full payload, then close stdin so the host's
        ;; read-string sees EOF after a complete form.
        (.write stdin (.getBytes ^String payload-edn "UTF-8"))
        (.close stdin)
        (let [exit (.waitFor proc)]
          (try (.join ^Thread stdout-drain 1000) (catch Throwable _))
          (try (.join ^Thread stderr-drain 1000) (catch Throwable _))
          exit)
        (finally
          (remove-shutdown-hook! shutdown-hook))))))

;; -- Top-level task ---------------------------------------------------

(defn- help-requested? [args]
  (boolean (some #{"--help" "-h"} args)))

(defn- print-help
  []
  (println "Run skeptic on this project's source- and test-paths.")
  (println "")
  (println "Usage: lein skeptic [OPTIONS]")
  (println "")
  (println "See README for the full CLI surface. The launcher forwards all")
  (println "options to the Skeptic host JVM, where they are parsed by")
  (println "skeptic.cli.options."))

(defn- run-skeptic
  [project args verbose?]
  (vlog verbose? "discovering cljs sources")
  (let [paths      (discover-cljs-paths project)
        _          (vlog verbose? "locating skeptic jar")
        skeptic-jar (locate-skeptic-jar project verbose?)
        _          (vlog verbose? "resolving host-jars")
        host-jars  (resolve-host-jars project skeptic-jar verbose?)
        _          (vlog verbose? "resolving worker-jars")
        worker-jars (resolve-worker-jars project skeptic-jar verbose?)
        _          (vlog verbose? "resolving plugin-jars")
        plugin-jars (resolve-plugin-jars project verbose?)
        _          (vlog verbose? "spawning worker JVM")
        worker-state (spawn-project-worker! project paths worker-jars plugin-jars
                                             skeptic-jar verbose?)]
    (try
      (let [worker-port (:port worker-state)]
        (vlog verbose? (str "spawning host JVM, worker port=" worker-port))
        (run-host-jvm host-jars project paths worker-port args verbose?))
      (finally
        (vlog verbose? "tearing down worker")
        (when-let [stop-fn (:stop-fn worker-state)]
          (try (stop-fn) (catch Throwable _)))))))

(defn skeptic
  {:doc "Run skeptic on this project's source- and test-paths. See README for options."}
  [project & args]
  (cond
    (help-requested? args)
    (print-help)

    :else
    (let [verbose? (boolean (some #{"--verbose" "-v"} args))
          exit-code (run-skeptic project args verbose?)]
      (when (and (integer? exit-code) (pos? exit-code))
        (System/exit exit-code)))))
