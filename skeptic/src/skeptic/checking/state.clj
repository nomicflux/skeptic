(ns skeptic.checking.state
  (:require [schema.core :as s]))

(defrecord ProjectState
  [dict
   accessor-summaries
   per-ns
   per-ns-failures
   cljs-state
   project-discovery
   var-provs])

(s/defschema ProjectStateSchema (s/pred (partial instance? ProjectState) 'ProjectState))
