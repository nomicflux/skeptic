(ns skeptic.malli-spec.collect
  "Malli intake stream. Hermetic from Plumatic by construction:
  - reads :malli/schema Var-meta directly (a Malli-only convention; Plumatic
    never writes :malli/schema).
  - projects (malli.core/function-schemas) for compile-time-registered
    m/=> and mx/defn entries.
  Does NOT call malli.instrument/collect!, which validates each schema as a
  function schema (`[:=> ...]`) and rejects non-function shapes that the
  bridge admits as Dyn (e.g. `:map` Var-meta on a value Var).

  Plumatic's `:schema` Var-meta channel — which mx/defn also writes raw
  Malli vectors into — is intentionally not consulted here."
  (:require [malli.core :as m]
            [skeptic.analysis.malli-spec.bridge :as amb]))

(defn- malli-declaration-error-result
  [ns-sym qualified-sym entry-meta e]
  {:report-kind :exception
   :phase :malli-declaration
   :blame qualified-sym
   :enclosing-form qualified-sym
   :namespace ns-sym
   :location (assoc (select-keys entry-meta [:file :line :column :end-line :end-column])
                    :source :malli)
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e) (str e))
   :exception-data (ex-data e)})

(defn- registry-entries
  [ns-sym]
  (when-let [projection (get (m/function-schemas) ns-sym)]
    (map (fn [[fn-sym {:keys [schema]}]]
           [fn-sym schema (some-> (ns-resolve (the-ns ns-sym) fn-sym) meta)])
         projection)))

(defn- var-meta-entries
  [ns-sym registered-syms]
  (keep (fn [[fn-sym v]]
          (when-let [schema (:malli/schema (meta v))]
            (when-not (registered-syms fn-sym)
              [fn-sym schema (meta v)])))
        (ns-interns ns-sym)))

(defn- admit-entry
  [ns-sym {:keys [entries errors]} [fn-sym schema entry-meta]]
  (let [qualified-sym (symbol (name ns-sym) (name fn-sym))]
    (try
      {:entries (assoc entries qualified-sym {:name (str qualified-sym)
                                              :malli-spec (amb/admit-malli-spec schema)})
       :errors errors}
      (catch Exception e
        {:entries entries
         :errors (conj errors (malli-declaration-error-result ns-sym qualified-sym entry-meta e))}))))

(defn ns-malli-spec-results
  [_opts ns-sym]
  (require ns-sym)
  (let [registered (registry-entries ns-sym)
        registered-syms (into #{} (map first) registered)
        meta-only (var-meta-entries ns-sym registered-syms)]
    (reduce (partial admit-entry ns-sym)
            {:entries {} :errors []}
            (concat registered meta-only))))

(defn ns-malli-specs
  [opts ns]
  (:entries (ns-malli-spec-results opts ns)))
