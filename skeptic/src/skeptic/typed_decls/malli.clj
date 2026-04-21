(ns skeptic.typed-decls.malli
  (:require [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.malli-spec.collect :as mcollect]
            [skeptic.provenance :as prov]
            [skeptic.schema.collect :as scollect]))

(defn desc->type
  [prov {:keys [malli-spec]}]
  (amb/malli-spec->type malli-spec prov))

(defn- desc->provenance
  [_desc ns qualified-sym]
  (prov/make-provenance :malli-spec qualified-sym ns nil))

(defn- empty-result [] {:dict {} :provenance {} :ignore-body #{} :errors []})

(defn- convert-desc
  [ns qualified-sym desc]
  (let [prov (desc->provenance desc ns qualified-sym)]
    {:dict {qualified-sym (desc->type prov desc)}
     :provenance {qualified-sym prov}
     :ignore-body #{}
     :errors []}))

(defn- safe-convert
  [ns qualified-sym desc]
  (try
    (convert-desc ns qualified-sym desc)
    (catch Exception e
      {:dict {}
       :provenance {}
       :ignore-body #{}
       :errors [(scollect/declaration-error-result
                 :malli-declaration
                 (symbol (ns-name ns))
                 qualified-sym
                 (resolve qualified-sym)
                 e)]})))

(defn- merge-two
  [a b]
  {:dict (merge (:dict a) (:dict b))
   :provenance (merge (:provenance a) (:provenance b))
   :ignore-body (into (:ignore-body a) (:ignore-body b))
   :errors (into (:errors a) (:errors b))})

(defn typed-ns-malli-results
  [opts ns]
  (let [{:keys [entries errors]} (mcollect/ns-malli-spec-results opts ns)]
    (reduce (fn [acc [qualified-sym desc]]
              (merge-two acc (safe-convert ns qualified-sym desc)))
            (-> (empty-result) (update :errors into errors))
            entries)))
