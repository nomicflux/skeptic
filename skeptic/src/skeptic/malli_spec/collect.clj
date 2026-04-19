(ns skeptic.malli-spec.collect
  (:require [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.malli-spec.bridge :as amb]))

(defn- malli-declaration-error-result
  [ns-sym qualified-sym v e]
  {:report-kind :exception
   :phase :malli-declaration
   :blame qualified-sym
   :enclosing-form qualified-sym
   :namespace ns-sym
   :location (select-keys (meta v) [:file :line :column :end-line :end-column])
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e) (str e))
   :exception-data (ex-data e)})

(defn ns-malli-spec-results
  [_opts ns]
  (binding [*ns* (the-ns ns)]
    (reduce (fn [{:keys [entries errors] :as acc} v]
              (if-let [qualified-sym (sb/qualified-var-symbol v)]
                (if (and (:malli/schema (meta v))
                         (not (:macro (meta v))))
                  (try
                    (let [malli-spec (amb/admit-malli-spec (:malli/schema (meta v)))]
                      {:entries (assoc entries qualified-sym {:name (str qualified-sym)
                                                               :malli-spec malli-spec})
                       :errors errors})
                    (catch Exception e
                      {:entries entries
                       :errors (conj errors (malli-declaration-error-result (symbol (ns-name ns)) qualified-sym v e))}))
                  acc)
                acc))
            {:entries {} :errors []}
            (-> ns symbol ns-interns vals))))

(defn ns-malli-specs
  [opts ns]
  (:entries (ns-malli-spec-results opts ns)))
