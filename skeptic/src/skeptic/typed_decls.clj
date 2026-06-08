(ns skeptic.typed-decls
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.native-fns :as native-fns]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.cljs.analyzer-driver.schema :as ads]
            [skeptic.malli-spec.collect.cljs :as malli-collect-cljs]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]
            [skeptic.schema.collect :as collect]
            [skeptic.schema.collect.clj-source :as clj-source]
            [skeptic.schema.collect.cljs :as schema-collect-cljs]
            [skeptic.schema.discovery :as discovery]
            [skeptic.typed-decls.malli :as typed-decls.malli]))

(defn desc->type
  [prov {:keys [schema]} form-descriptor form-refs]
  (ab/schema->type prov schema form-descriptor form-refs))

(s/defn ^:private desc->provenance :- provs/Provenance
  [_desc         :- s/Any
   ns            :- (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
   qualified-sym :- (s/maybe s/Symbol)
   lang          :- provs/Lang]
  (prov/make-provenance :schema qualified-sym ns nil [] lang))

(defn convert-desc
  [ns qualified-sym desc lang form-refs]
  (let [prov (desc->provenance desc ns qualified-sym lang)
        form-descriptor (and form-refs (get form-refs qualified-sym))]
    {:dict {qualified-sym (desc->type prov desc form-descriptor form-refs)}
     :provenance {qualified-sym prov}
     :ignore-body (if (:skeptic/ignore-body? desc) #{qualified-sym} #{})
     :errors []}))

(defn- safe-convert
  [ns qualified-sym desc lang form-refs]
  (try
    (convert-desc ns qualified-sym desc lang form-refs)
    (catch Exception e
      {:dict {}
       :provenance {}
       :ignore-body #{}
       :errors [(collect/declaration-error-result ns qualified-sym
                                                  qualified-sym e)]})))

(defn- empty-result [] {:dict {} :provenance {} :ignore-body #{} :errors []})

(defn native-result []
  {:dict native-fns/native-fn-dict
   :provenance native-fns/native-fn-provenance
   :ignore-body #{}
   :errors []})

(defn- merge-two
  [a b]
  {:dict (merge (:dict a) (:dict b))
   :provenance (merge (:provenance a) (:provenance b))
   :ignore-body (into (:ignore-body a) (:ignore-body b))
   :errors (into (:errors a) (:errors b))})

(defn merge-type-dicts
  [results]
  (let [all-syms (into #{} (mapcat (comp keys :dict)) results)]
    (reduce
     (fn [acc sym]
       (let [types (keep #(get (:dict %) sym) results)
             provs (keep #(get (:provenance %) sym) results)
             merged-type (ato/intersection (vec (at/dedup-types types)))
             merged-prov (reduce prov/merge-provenances (first provs) (rest provs))]
         (-> acc
             (assoc-in [:dict sym] merged-type)
             (assoc-in [:provenance sym] merged-prov))))
     (reduce merge-two (empty-result) results)
     all-syms)))

(defn- convert-descs
  [ns descs lang form-refs]
  (reduce (fn [acc [qualified-sym desc]]
            (merge-two acc (safe-convert ns qualified-sym desc lang form-refs)))
          (empty-result)
          descs))

(defn convert-collected
  "Convert a `{:entries :errors}` collector result to the merged type-dict shape,
  stamping each provenance with the given lang. Used by both the JVM admission
  path (via `typed-ns-results`) and the cljs admission path (via
  `skeptic.checking.pipeline/namespace-dict`)."
  [ns lang form-refs {:keys [entries errors]}]
  (-> (convert-descs ns entries lang form-refs)
      (update :errors into errors)))

(defn typed-ns-results
  ([opts ns lang form-refs entries]
   (if (:plumatic-disable opts)
     (empty-result)
     (let [collected (clj-source/ns-schema-results-clj ns entries)
           result (convert-collected ns lang form-refs collected)
           overrides (or (:skeptic/type-overrides opts) {})]
       (reduce (fn [acc [sym v]]
                 (-> acc
                     (assoc-in [:dict sym] v)
                     (assoc-in [:provenance sym]
                               (prov/make-provenance :type-override sym nil nil [] lang))))
               result
               overrides))))
  ([opts ns lang form-refs entries _ignored-declarations]
   (typed-ns-results opts ns lang form-refs entries)))

(s/defn ^:private require-cljs-per-file :- ads/SourceFileAnalysis
  [cljs-state  :- {s/Any s/Any}
   source-file :- s/Any
   ns-sym      :- s/Symbol]
  (or (get cljs-state source-file)
      (throw (ex-info "cljs requires cljs-state with per-file entry for source-file"
                      {:ns ns-sym :source-file (some-> source-file str)}))))

(defn- clj-namespace-dict
  [opts ns-sym var-provs form-refs entries]
  (binding [ab/*var-provs* var-provs]
    (let [forms (mapv :source-form entries)
          aliases (discovery/source-form-aliases forms)
          schema-result (typed-ns-results opts ns-sym :clj form-refs entries)
          malli-result (typed-decls.malli/typed-ns-malli-results opts ns-sym :clj aliases entries)]
      (merge-type-dicts [schema-result malli-result (native-result)]))))

(defn- cljs-namespace-dict
  [opts ns-sym source-file cljs-state var-provs form-refs]
  (if-not (contains? cljs-state source-file)
    (let [schema-result (convert-collected ns-sym :cljs form-refs {:entries {} :errors []})
          malli-result  (typed-decls.malli/convert-collected ns-sym :cljs {:entries {} :errors []})]
      (merge-type-dicts [schema-result malli-result (native-result)]))
    (let [{:keys [ns-ast entries]} (require-cljs-per-file cljs-state source-file ns-sym)
          top-asts (filterv :op (mapv :ast (filterv :ast (or entries []))))
          schema-result (if (:plumatic-disable opts)
                          (convert-collected ns-sym :cljs form-refs {:entries {} :errors []})
                          (binding [ab/*var-provs* var-provs]
                            (convert-collected
                             ns-sym :cljs form-refs
                             (schema-collect-cljs/ns-schema-results-cljs
                              ns-ast source-file ns-sym top-asts))))
          malli-result (if (:malli-disable opts)
                         (typed-decls.malli/convert-collected ns-sym :cljs {:entries {} :errors []})
                         (binding [ab/*var-provs* var-provs]
                           (typed-decls.malli/convert-collected
                            ns-sym :cljs
                            (malli-collect-cljs/ns-malli-spec-results-cljs
                             source-file ns-sym (or entries [])))))]
      (merge-type-dicts [schema-result malli-result (native-result)]))))

(s/defn namespace-dict :- s/Any
  [opts ns-sym :- s/Symbol source-file lang cljs-state var-provs form-refs entries]
  (case lang
    :clj  (clj-namespace-dict opts ns-sym var-provs form-refs entries)
    :cljs (cljs-namespace-dict opts ns-sym source-file cljs-state var-provs form-refs)
    :both (merge-type-dicts
           [(clj-namespace-dict opts ns-sym var-provs form-refs entries)
            (cljs-namespace-dict opts ns-sym source-file cljs-state var-provs form-refs)])))
