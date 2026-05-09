(ns skeptic.typed-decls.malli
  (:require [schema.core :as s]
            [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.malli-spec.collect :as mcollect]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]
            [skeptic.schema.collect :as scollect]))

(defn desc->type
  [prov {:keys [malli-spec]}]
  (amb/malli-spec->type prov malli-spec))

(s/defn ^:private desc->provenance :- provs/Provenance
  [_desc         :- s/Any
   ns            :- (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
   qualified-sym :- (s/maybe s/Symbol)
   lang          :- provs/Lang]
  (prov/make-provenance :malli qualified-sym ns nil [] lang))

(defn- empty-result [] {:dict {} :provenance {} :ignore-body #{} :errors []})

(defn- convert-desc
  [ns qualified-sym desc lang]
  (let [prov (desc->provenance desc ns qualified-sym lang)]
    {:dict {qualified-sym (desc->type prov desc)}
     :provenance {qualified-sym prov}
     :ignore-body #{}
     :errors []}))

(defn- safe-convert
  [ns qualified-sym desc lang]
  (try
    (convert-desc ns qualified-sym desc lang)
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

(defn convert-collected
  "Convert a `{:entries :errors}` Malli collector result to the merged
  type-dict shape, stamping each provenance with the given lang. Used by
  both the JVM admission path (via `typed-ns-malli-results`) and the cljs
  admission path (via `skeptic.checking.pipeline/namespace-dict`)."
  [ns lang {:keys [entries errors]}]
  (reduce (fn [acc [qualified-sym desc]]
            (merge-two acc (safe-convert ns qualified-sym desc lang)))
          (-> (empty-result) (update :errors into errors))
          entries))

(defn typed-ns-malli-results
  [opts ns]
  (if (:malli-disable opts)
    (empty-result)
    (let [lang (or (:skeptic/lang opts) :clj)]
      (convert-collected ns lang (mcollect/ns-malli-spec-results opts ns)))))
