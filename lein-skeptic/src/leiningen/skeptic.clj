(ns leiningen.skeptic
  (:require [clojure.java.io :as io]
            [leiningen.core.classpath]
            [leiningen.core.main]
            [schema.core]
            [skeptic.cli.cljs.lein :as cljs-lein]
            [skeptic.cli.options :as cli-opts]
            [skeptic.core]
            [skeptic.profiling :as profiling]))

(defn skeptic
  {:doc (str "Run skeptic on this project's source- and test-paths.\n\n"
             "Usage: lein skeptic [OPTIONS]\n\n"
             "Options:\n"
             (:summary (cli-opts/parse [])))}
  [project & args]
  (let [paths (:source-paths (cljs-lein/discover-sources project))
        cp (vec (leiningen.core.classpath/get-classpath project))
        {:keys [options summary errors]} (cli-opts/parse args)]
    (cond
      (:help options) (println summary)
      errors          (do (doseq [e errors] (leiningen.core.main/warn e))
                          (leiningen.core.main/warn summary)
                          (leiningen.core.main/abort))
      :else
      (let [output-path (:output options)
            writer (when output-path (io/writer output-path))
            exit-code (try
                        (binding [*out* (or writer *out*)]
                          (schema.core/without-fn-validation
                            (profiling/run options (str (:root project) "/target")
                              (fn []
                                (apply skeptic.core/check-project
                                       (assoc options :worker-classpath cp)
                                       (:root project)
                                       paths)))))
                        (finally
                          (when writer
                            (.flush writer)
                            (.close writer))))]
        (System/exit exit-code)))))
