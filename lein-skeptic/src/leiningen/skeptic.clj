(ns leiningen.skeptic
  (:require [clojure.tools.cli :as cli]
            [leiningen.core.main]
            [leiningen.core.eval]
            [leiningen.core.project]
            [skeptic.core]))

(def skeptic-profile {:dependencies [['org.clojure/clojure  "1.11.1"]
                                     ['org.clojars.nomicflux/skeptic "0.7.1-SNAPSHOT"]]})

(def cli-options
  [["-v" "--verbose" "Turn on verbose logging"]
   ["-a" "--analyzer" "Use clojure.tools.analyzer to analyse code"]
   ["-k" "--keep-empty" "Print out checking results with empty error set"]
   ["-c" "--show-context" "Show context and resolution path on items"]
   ["-n" "--namespace NAMESPACE" "Only check specific namespace"]
   ["-p" "--porcelain" "Emit machine-readable JSONL (one JSON object per line)"]
   [nil  "--profile" "Profile the run (CPU, memory, wall-clock time)"]
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
       `(let [exit-code# (skeptic.profiling/run ~opts ~(str (:root project) "/target")
                   (fn [] (skeptic.core/check-project ~opts ~(:root project) ~@paths)))]
          (System/exit exit-code#))
       '(do (require 'skeptic.core) (require 'skeptic.profiling))))))
