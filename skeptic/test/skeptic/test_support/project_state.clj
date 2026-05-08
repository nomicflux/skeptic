(ns skeptic.test-support.project-state
  "Test helper for project-state-backed single-ns admission.
  Production goes through skeptic.checking.pipeline/project-state which
  precomputes a project-wide var-provs and threads it via opts. Tests that
  used to call (namespace-dict {} ns source-file) with empty opts route
  through this helper instead, which prepares the same opts shape."
  (:require [skeptic.checking.pipeline :as pipeline]))

(defn admit-ns
  "Project-state-backed single-ns admission. Returns the namespace-dict shape:
  {:dict ... :provenance ... :ignore-body ... :errors ...}."
  [ns-sym source-file]
  (require ns-sym)
  (let [project-disc (pipeline/project-discovery [[ns-sym source-file]])
        var-provs (pipeline/project-var-provs {} project-disc)
        opts {:skeptic/project-discovery project-disc
              :skeptic/var-provs var-provs}]
    (pipeline/namespace-dict opts ns-sym source-file)))
