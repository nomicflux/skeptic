(ns skeptic.test-support.project-state
  "Test helper for project-state-backed single-ns admission.
  Production goes through skeptic.checking.pipeline/project-state which
  precomputes a project-wide var-provs and threads it via opts. Tests
  route through this helper instead, which prepares the same opts shape
  plus the per-file lang and cljs-state that namespace-dict requires."
  (:require [skeptic.checking.pipeline :as pipeline]))

(defn admit-ns
  "Project-state-backed single-ns admission. Returns the namespace-dict shape:
  {:dict ... :provenance ... :ignore-body ... :errors ...}."
  [ns-sym source-file]
  (require ns-sym)
  (let [project-disc (pipeline/project-discovery [[ns-sym source-file]])
        var-provs (pipeline/project-var-provs {} project-disc)
        opts {:skeptic/project-discovery project-disc
              :skeptic/var-provs var-provs}
        lang (pipeline/lang-of-source-file source-file)
        cljs-state (pipeline/preload-cljs-state! false [[ns-sym source-file lang]])]
    (pipeline/namespace-dict opts ns-sym source-file lang cljs-state)))
