(ns skeptic.cli.options
  "Shared CLI option vector and parser for both the Leiningen plugin
  (leiningen.skeptic) and the deps.edn entrypoint (skeptic.cli.main).
  Keys produced here land directly in the opts map consumed by
  skeptic.core/check-project."
  (:require [clojure.tools.cli :as cli]))

(def core-cli-options
  [["-v" "--verbose" "Turn on verbose logging"]
   ["-a" "--analyzer" "Use clojure.tools.analyzer to analyse code"]
   ["-k" "--keep-empty" "Print out checking results with empty error set"]
   ["-c" "--show-context" "Show context and resolution path on items"]
   ["-n" "--namespace NAMESPACE"
    "Only check the specified namespace (repeatable; comma-separated values supported)"
    :multi true
    :update-fn (fnil conj [])]
   [nil  "--explain-full" "Show fully expanded structural forms in type-mismatch output (disable name-folding)"]
   ["-p" "--porcelain" "Emit machine-readable JSONL (one JSON object per line)"]
   [nil  "--plumatic-disable" "Disable Plumatic Schema intake (skip s/defn / s/def / s/defschema declarations and :skeptic/type-overrides)"]
   [nil  "--malli-disable" "Disable Malli intake (skip m/=>, mx/defn, and :malli/schema Var-meta)"]
   [nil  "--debug" "Emit raw internal state for cross-environment diffing"]
   [nil  "--profile" "Profile the run (CPU, memory, wall-clock time)"]
   ["-o" "--output OUTPUT_FILE" "Write skeptic output to this file instead of stdout"]
   ["-h" "--help"]])

(defn parse
  ([args] (parse args nil))
  ([args extra-options]
   (cli/parse-opts args (vec (concat core-cli-options extra-options)))))
