(ns leiningen.skeptic
  (:require [leiningen.core.main]
            [leiningen.core.eval]
            [leiningen.core.project]
            [schema.core]
            [skeptic.cli.cljs.lein :as cljs-lein]
            [skeptic.cli.options :as cli-opts]
            [skeptic.core]))

(def skeptic-profile {:dependencies [['org.clojure/clojure  "1.11.1"]
                                     ['org.clojars.nomicflux/skeptic "0.9.0-rc3"
                                      :exclusions ['org.clojure/tools.deps]]
                                     ['prismatic/schema "1.4.1"]]})

(defn skeptic
  {:doc (str "Run skeptic on this project's source- and test-paths.\n\n"
             "Usage: lein skeptic [OPTIONS]\n\n"
             "Options:\n"
             (:summary (cli-opts/parse [])))}
  [project & args]
  (let [profile (or (:skeptic (:profiles project)) skeptic-profile)
        paths (:source-paths (cljs-lein/discover-sources project))
        {:keys [options summary errors]} (cli-opts/parse args)]
    (cond
      (:help options) (println summary)
      errors          (do (doseq [e errors] (leiningen.core.main/warn e))
                          (leiningen.core.main/warn summary)
                          (leiningen.core.main/abort))
      :else
      (leiningen.core.eval/eval-in-project
       (leiningen.core.project/merge-profiles project [profile])
       `(let [output-path# ~(:output options)
              writer# (when output-path# (clojure.java.io/writer output-path#))
              exit-code# (try
                           (binding [*out* (or writer# *out*)]
                             (schema.core/without-fn-validation
                               (skeptic.profiling/run ~options ~(str (:root project) "/target")
                                 (fn [] (skeptic.core/check-project ~options ~(:root project) ~@paths)))))
                           (finally
                             (when writer# (.flush writer#) (.close writer#))))]
          (System/exit exit-code#))
       '(do (require 'skeptic.core) (require 'schema.core) (require 'skeptic.profiling) (require 'clojure.java.io))))))
