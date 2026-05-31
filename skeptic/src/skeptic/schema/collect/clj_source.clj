(ns skeptic.schema.collect.clj-source
  "Host-side JVM Plumatic admission from worker-captured evaluated schema
  values. The worker loads the project namespace and reads Var metadata; the
  host decodes the EDN schema payload and feeds the existing SchemaDesc builder.
  No source-form schema interpretation and no host project namespace loading."
  (:require [skeptic.schema.collect :as collect]
            [skeptic.schema.wire :as schema-wire]))

(defn- error-result
  [ns-sym qualified-sym entry e]
  (collect/declaration-error-result
   ns-sym
   qualified-sym
   (with-meta (or (:name (:plumatic-schema entry)) qualified-sym)
     (meta (:source-form entry)))
   e))

(defn- admit-entry
  [ns-sym {:keys [entries errors]} {:keys [plumatic-schema] :as entry}]
  (if-not plumatic-schema
    {:entries entries :errors errors}
    (let [{:keys [qualified-sym name arglists schema ignore-body?]} plumatic-schema]
      (try
        (let [desc (collect/collect-schemas
                    {:schema (schema-wire/decode-schema schema)
                     :ns ns-sym
                     :name name
                     :arglists arglists})]
          {:entries (assoc entries qualified-sym
                           (cond-> desc ignore-body? (assoc :skeptic/ignore-body? true)))
           :errors errors})
        (catch Exception e
          {:entries entries
           :errors (conj errors (error-result ns-sym qualified-sym entry e))})))))

(defn ns-schema-results-clj
  "Per-namespace JVM Plumatic admission from worker-shipped clj-state entries."
  [ns-sym entries]
  (reduce (partial admit-entry ns-sym)
          {:entries {} :errors []}
          entries))
