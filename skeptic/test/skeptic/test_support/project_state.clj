(ns skeptic.test-support.project-state
  "Test helper for project-state-backed single-ns admission.
  Production goes through skeptic.checking.pipeline/project-state which
  precomputes a project-wide var-provs and threads it via opts. Tests
  route through this helper instead, which prepares the same opts shape
  plus the per-file lang and cljs-state that namespace-dict requires.

  Admission is hermetic and worker-sourced (the worker reads the project's
  source and ships inert forms), so this helper threads the suite-wide shared
  worker's connection into project-state — callers MUST run under the
  `skeptic.test-support.shared-worker/with-shared-worker` :once fixture."
  (:require [skeptic.analysis.class-oracle :as oracle]
            [skeptic.checking.pipeline :as pipeline]))

(defn admit-ns
  "Project-state-backed single-ns admission, mirroring production. Returns the
  namespace-dict shape: {:dict ... :provenance ... :ignore-body ... :errors ...}.
  The :dict is enriched (ConditionalT descriptors filled in via
  accessor-summaries) the same way production's `pipeline/project-state` does,
  so callers see the same dict shape that downstream analysis assumes."
  [ns-sym source-file]
  (let [ps (pipeline/project-state {:worker-conn oracle/*worker-conn*} [[ns-sym source-file]])
        per-ns-entry (get-in ps [:per-ns ns-sym])
        failure (get-in ps [:per-ns-failures ns-sym])]
    (when failure
      (throw (or (:exception failure)
                 (ex-info "namespace-dict failed" {:ns ns-sym :failure failure}))))
    (assoc per-ns-entry :dict (:dict ps))))
