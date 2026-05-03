(ns leiningen.skeptic
  (:require [clojure.tools.cli :as cli]
            [leiningen.core.main]
            [leiningen.core.eval]
            [leiningen.core.project]
            [schema.core]
            [skeptic.core]))

(def skeptic-profile {:dependencies [['org.clojure/clojure  "1.11.1"]
                                     ['org.clojars.nomicflux/skeptic "0.8.0"]]})

(def cli-options
  [["-v" "--verbose" "Turn on verbose logging"]
   ["-a" "--analyzer" "Use clojure.tools.analyzer to analyse code"]
   ["-k" "--keep-empty" "Print out checking results with empty error set"]
   ["-c" "--show-context" "Show context and resolution path on items"]
   ["-n" "--namespace NAMESPACE" "Only check specific namespace"]
   [nil  "--explain-full" "Show fully expanded structural forms in type-mismatch output (disable name-folding)"]
   ["-p" "--porcelain" "Emit machine-readable JSONL (one JSON object per line)"]
   [nil  "--debug" "Emit raw internal state for cross-environment diffing"]
   [nil  "--profile" "Profile the run (CPU, memory, wall-clock time)"]
   ["-o" "--output OUTPUT_FILE" "Write skeptic output to this file instead of stdout"]
   ["-h" "--help"]])

(defn ^{:doc "Run skeptic on this project's source- and test-paths.

Usage: lein skeptic [OPTIONS]

Options:
  -v, --verbose             Turn on verbose logging
  -a, --analyzer            Use clojure.tools.analyzer to analyse code
  -k, --keep-empty          Print out checking results with empty error set
  -c, --show-context        Show context and resolution path on items
  -n, --namespace NS        Only check the given namespace
      --explain-full        Show fully expanded structural forms in type-mismatch output (disable name-folding)
  -p, --porcelain           Emit machine-readable JSONL (one JSON object per line)
      --debug               Emit raw internal state for cross-environment diffing
      --profile             Profile the run (CPU, memory, wall-clock time)
  -o, --output FILE         Write skeptic output to this file instead of stdout
  -h, --help                Show this option summary"}
  skeptic
  [project & args]
  (let [profile (or (:skeptic (:profiles project)) skeptic-profile)
        paths (concat (:source-paths project) (:test-paths project))
        {{:keys [help errors] :as opts} :options summary :summary} (cli/parse-opts args cli-options)]
    (if (or help errors)
      (println summary)
      (leiningen.core.eval/eval-in-project
       (leiningen.core.project/merge-profiles project [profile])
       `(let [output-path# ~(:output opts)
              writer# (when output-path# (clojure.java.io/writer output-path#))
              exit-code# (try
                           (binding [*out* (or writer# *out*)]
                             (schema.core/without-fn-validation
                               (skeptic.profiling/run ~opts ~(str (:root project) "/target")
                                 (fn [] (skeptic.core/check-project ~opts ~(:root project) ~@paths)))))
                           (finally
                             (when writer# (.flush writer#) (.close writer#))))]
          (System/exit exit-code#))
       '(do (require 'skeptic.core) (require 'schema.core) (require 'skeptic.profiling) (require 'clojure.java.io))))))
