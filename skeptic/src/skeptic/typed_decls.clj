(ns skeptic.typed-decls
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]
            [skeptic.schema.collect :as collect]))

(defn desc->type
  ([prov desc] (desc->type prov desc nil))
  ([prov {:keys [schema]} form-descriptor]
   (ab/schema->type prov schema form-descriptor)))

(s/defn ^:private desc->provenance :- provs/Provenance
  [_desc         :- s/Any
   ns            :- (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
   qualified-sym :- (s/maybe s/Symbol)]
  (prov/make-provenance :schema qualified-sym ns nil))

(defn convert-desc
  [ns qualified-sym desc]
  (let [declared-var (resolve qualified-sym)
        prov (desc->provenance desc ns qualified-sym)
        form-descriptor (and ab/*form-refs* declared-var
                             (.get ^java.util.IdentityHashMap ab/*form-refs* declared-var))]
    {:dict {qualified-sym (desc->type prov desc form-descriptor)}
     :provenance {qualified-sym prov}
     :ignore-body (if (:skeptic/ignore-body? desc) #{qualified-sym} #{})
     :errors []}))

(defn- safe-convert
  [ns qualified-sym desc]
  (try
    (convert-desc ns qualified-sym desc)
    (catch Exception e
      {:dict {}
       :provenance {}
       :ignore-body #{}
       :errors [(collect/declaration-error-result ns qualified-sym
                                                  (resolve qualified-sym) e)]})))

(defn- empty-result [] {:dict {} :provenance {} :ignore-body #{} :errors []})

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
             merged-type (ato/intersection (distinct types))
             merged-prov (reduce prov/merge-provenances (first provs) (rest provs))]
         (-> acc
             (assoc-in [:dict sym] merged-type)
             (assoc-in [:provenance sym] merged-prov))))
     (reduce merge-two (empty-result) results)
     all-syms)))

(defn- convert-descs
  [ns descs]
  (reduce (fn [acc [qualified-sym desc]]
            (merge-two acc (safe-convert ns qualified-sym desc)))
          (empty-result)
          descs))

(defn typed-ns-results
  [opts ns]
  (let [{:keys [entries errors]} (collect/ns-schema-results opts ns)
        result (convert-descs ns entries)
        result (update result :errors into errors)
        overrides (or (:skeptic/type-overrides opts) {})]
    (reduce (fn [acc [sym v]]
              (-> acc
                  (assoc-in [:dict sym] v)
                  (assoc-in [:provenance sym]
                            (prov/make-provenance :type-override sym nil nil))))
            result
            overrides)))
