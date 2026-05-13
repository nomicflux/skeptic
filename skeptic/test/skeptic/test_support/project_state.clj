(ns skeptic.test-support.project-state
  "Test helper for project-state-backed single-ns admission.
  Production goes through skeptic.checking.pipeline/project-state which
  precomputes a project-wide var-provs and threads it via opts. Tests
  route through this helper instead, which prepares the same opts shape
  plus the per-file lang and cljs-state that namespace-dict requires."
  (:require [skeptic.checking.pipeline :as pipeline]))

(defn admit-ns
  "Project-state-backed single-ns admission, mirroring production. Returns the
  namespace-dict shape: {:dict ... :provenance ... :ignore-body ... :errors ...}.
  The :dict is enriched (ConditionalT descriptors filled in via
  accessor-summaries) the same way production's `pipeline/project-state` does,
  so callers see the same dict shape that downstream analysis assumes."
  [ns-sym source-file]
  (require ns-sym)
  (let [ps (pipeline/project-state {} [[ns-sym source-file]])
        per-ns-entry (get-in ps [:per-ns ns-sym])
        failure (get-in ps [:per-ns-failures ns-sym])]
    (when failure
      (throw (or (:exception failure)
                 (ex-info "namespace-dict failed" {:ns ns-sym :failure failure}))))
    (assoc per-ns-entry :dict (:dict ps))))
