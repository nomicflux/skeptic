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

(defn- run-skeptic
  [project args]
  (let [paths (:source-paths (cljs-lein/discover-sources project))
        project-cp (vec (leiningen.core.classpath/get-classpath project))
        worker-classpath-entries (required-var 'skeptic.worker.classpath/worker-classpath-entries)
        profiling-run (required-var 'skeptic.profiling/run)
        check-project (required-var 'skeptic.core/check-project)
        cp (worker-classpath-entries project-cp)
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
