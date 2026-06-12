(ns skeptic.checking.state
  (:require [schema.core :as s]))

(defrecord ProjectState
  [dict
   accessor-summaries
   per-ns
   per-ns-failures
   cljs-state
   clj-state
   project-discovery
   var-provs
   user-fn-summaries
   shadowed-files
   worker-conn])

(s/defschema ProjectStateSchema (s/pred (partial instance? ProjectState) 'ProjectState))
