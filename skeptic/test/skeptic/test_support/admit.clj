(ns skeptic.test-support.admit
  "Hermetic admission helpers for tests."
  (:require [skeptic.analysis.class-oracle :as oracle]
            [skeptic.worker.client :as wc]
            [skeptic.worker.wire :as wire]
            [skeptic.schema.discovery :as discovery]))

(defn entries
  "The clj-state entries the worker ships for this file."
  [ns-sym source-file]
  (if oracle/*worker-conn*
    (let [{:keys [entries read-failure]} (wc/ask oracle/*worker-conn*
                                                 {:op "analyze-namespace"
                                                  :ns (str ns-sym)
                                                  :source-file (str source-file)})]
      (when read-failure
        (throw (ex-info read-failure {:ns ns-sym :source-file source-file})))
      (mapv (fn [{:keys [source-form source-form-meta ast ast-meta] :as entry}]
              (cond-> (dissoc entry :source-form-meta :ast-meta)
                source-form-meta (assoc :source-form (wire/apply-form-meta source-form source-form-meta))
                ast-meta (assoc :ast (wire/apply-form-meta ast ast-meta))))
            entries))
    (throw (ex-info "admit/entries requires skeptic.test-support.shared-worker/with-shared-worker"
                    {:ns ns-sym :source-file source-file}))))

(defn plumatic-args
  "Returns `{:entries}` for `typed-ns-results` admission."
  [ns-sym source-file]
  (let [entries (entries ns-sym source-file)]
    {:entries entries
     ;; Compatibility for older direct tests that destructure
     ;; {:keys [aliases declarations]} and pass aliases in the fifth slot.
     :aliases entries
     :declarations nil}))

(defn malli-args
  "Returns `{:aliases :entries}` for `typed-ns-malli-results` admission."
  [ns-sym source-file]
  (let [es (entries ns-sym source-file)]
    {:aliases (discovery/source-form-aliases (mapv :source-form es))
     :entries es}))
