(ns leiningen.skeptic
  (:require [clojure.java.io :as io]
            [leiningen.core.classpath]
            [leiningen.core.main]
            [schema.core]
            [skeptic.cli.cljs.lein :as cljs-lein]
            [skeptic.cli.options :as cli-opts]))

(defn- required-var
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "Could not resolve " sym) {:sym sym}))))

(defn- resolve-worker-jars
  "Resolve Skeptic's worker dependency declaration via leiningen's aether
   wrapper. The declaration lives in `skeptic.worker.deps/worker-deps` —
   Skeptic owns it as data, lein resolves it as a synthetic project.
   `resolve-managed-dependencies` falls back to lein's default-repositories
   when a project map omits `:repositories`."
  []
  (let [worker-deps (deref (required-var 'skeptic.worker.deps/worker-deps))
        synthetic-project {:dependencies worker-deps}]
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
  [project]
  (mapv #(.getAbsolutePath ^java.io.File %)
        (leiningen.core.classpath/resolve-managed-dependencies
          :plugins :managed-dependencies project)))

(defn- run-skeptic
  [project args]
  (let [paths (:source-paths (cljs-lein/discover-sources project))
        base-cp (vec (leiningen.core.classpath/get-classpath project))
        plugin-jars (resolve-plugin-jars project)
        project-cp (vec (distinct (concat base-cp plugin-jars)))
        worker-jars (resolve-worker-jars)
        worker-classpath-entries (required-var 'skeptic.worker.classpath/worker-classpath-entries)
        profiling-run (required-var 'skeptic.profiling/run)
        check-project (required-var 'skeptic.core/check-project)
        cp (worker-classpath-entries worker-jars project-cp)
        {:keys [options summary errors]} (cli-opts/parse args)]
    (cond
      (:help options) (println summary)
      errors          (do (doseq [e errors] (leiningen.core.main/warn e))
                          (leiningen.core.main/warn summary)
                          (leiningen.core.main/abort))
      :else
      (let [output-path (:output options)
            writer (when output-path (io/writer output-path))]
        (try
          (binding [*out* (or writer *out*)]
            (schema.core/without-fn-validation
              (profiling-run options (str (:root project) "/target")
                (fn []
                  (apply check-project
                         (assoc options :worker-classpath cp)
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
