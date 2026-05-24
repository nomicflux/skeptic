(ns skeptic.tool
  "Clojure CLI tool entrypoints for hermetic deps.edn-side Skeptic runs."
  (:require [skeptic.cli.main :as main]))

(defn check
  "Run Skeptic as a Clojure CLI tool.

  Invoke with:

    clj -T:skeptic check

  The current process is the Skeptic tool/plugin runtime. The client project
  is selected by `:project-dir` and read as analysis input."
  [arg-map]
  (System/exit (main/check-project arg-map)))
