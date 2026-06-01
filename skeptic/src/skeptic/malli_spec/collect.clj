(ns skeptic.malli-spec.collect
  "Malli intake stream, hermetic from the project JVM. Reads two channels of
  inert Malli spec data off the worker-shipped clj-state entries — never the
  live `(malli.core/function-schemas)` registry or a loaded Var's metadata:

  1. `:malli/schema` Var-meta → the `:malli-schema` field the worker captured
     off the raw source-form (`skeptic.worker.server/project-entry`).
  2. `m/=>` → the spec vector at position 2 of the `(m/=> sym SPEC)` source-form.

  Specs are inert keyword/vector data; `amb/admit-malli-spec` (pinned Malli)
  does all interpretation host-side. No `malli.core` require here."
  (:require [skeptic.analysis.malli-spec.bridge :as amb]))

(defn malli-declaration-error-result
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

;; --- channel extraction (returns [fn-sym malli-spec-form] pairs) ---

(defn- resolve-head
  [aliases form]
  (when (and (seq? form) (symbol? (first form)))
    (let [head (first form)]
      (if-let [target (some-> head namespace symbol (->> (get aliases)))]
        (symbol (name target) (name head))
        head))))

(defn- channel-entries
  "All [fn-sym spec entry-meta] Malli declarations from one clj-state entry."
  [aliases {:keys [source-form malli-schema]}]
  (let [head (resolve-head aliases source-form)
        name-sym (when (seq? source-form) (second source-form))
        entry-meta (meta source-form)]
    (cond
      malli-schema [[name-sym malli-schema entry-meta]]
      (= head 'malli.core/=>) [[name-sym (nth source-form 2 nil) entry-meta]]
      :else nil)))

(defn- admit-entry
  [ns-sym {:keys [entries errors]} [fn-sym spec entry-meta]]
  (let [qualified-sym (symbol (name ns-sym) (name fn-sym))]
    (try
      {:entries (assoc entries qualified-sym {:name (str qualified-sym)
                                              :malli-spec (amb/admit-malli-spec spec)})
       :errors errors}
      (catch Exception e
        {:entries entries
         :errors (conj errors (malli-declaration-error-result ns-sym qualified-sym entry-meta e))}))))

(defn ns-malli-spec-results
  "Per-namespace Malli admission from worker-shipped clj-state entries. Inputs:
  - `ns-sym`: the namespace symbol.
  - `aliases`: the ns alias map (alias-sym → target-ns-sym) for head resolution.
  - `entries`: the ns's clj-state entries `[{:source-form :malli-schema} …]`.
  Returns `{:entries {qsym {:name :malli-spec}} :errors [...]}`."
  [ns-sym aliases entries]
  (reduce (partial admit-entry ns-sym)
          {:entries {} :errors []}
          (mapcat #(channel-entries aliases %) entries)))

(defn malli-admitted-qsyms
  "Qsym set the Malli admission collector would admit for ns-sym, without
  converting spec bodies. Used by pipeline var-provs so it cannot disagree with
  what ns-malli-spec-results admits."
  [ns-sym aliases entries]
  (into #{}
        (map (fn [[fn-sym _ _]] (symbol (name ns-sym) (name fn-sym))))
        (mapcat #(channel-entries aliases %) entries)))
