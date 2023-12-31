(ns leiningen.skeptic
  (:require [clojure.tools.cli :as cli]
            [leiningen.core.main]
            [leiningen.core.eval]
            [leiningen.core.project]
            [skeptic.core]))

(def skeptic-profile {:dependencies [['org.clojure/clojure  "1.11.1"]
                                     ['skeptic              "0.7.0-SNAPSHOT"]]})

(def cli-options
  [["-v" "--verbose" "Turn on verbose logging"]
   ["-k" "--keep-empty" "Print out checking results with empty error set"]
   ["-c" "--show-context" "Show context and resolution path on items"]
   ["-n" "--namespace NAMESPACE" "Only check specific namespace"]
   ["-h" "--help"]])

(defn skeptic
  [project & args]
  (let [profile (or (:skeptic (:profiles project)) skeptic-profile)
        paths (concat (:source-paths project) (:test-paths project))
        {{:keys [help errors] :as opts} :options summary :summary} (cli/parse-opts args cli-options)]
    (if (or help errors)
      (println summary)
      (leiningen.core.eval/eval-in-project
       (leiningen.core.project/merge-profiles project [profile])
       `(skeptic.core/get-project-schemas ~opts ~(:root project) ~@paths)
       '(require 'skeptic.core)))))
