(ns skeptic.utils
  (:require
   [skeptic.schema :as dschema]
   [skeptic.schematize :as schematize]
   [schema.core :as s]))

(defn walk-fn?
  [x]
  (schematize/into-coll fn? x))

(defn try-meta
  [x]
  (try (meta (schematize/try-resolve x))
       (catch Exception _e nil)))

(defn meta-var
  [x]
  (meta (intern (quote skeptic.schematize) x)))

(s/defn combine-descs :- dschema/SchemaDesc
  [{n1 :name s1 :schema o1 :output a1 :arglists} :- dschema/SchemaDesc
   {n2 :name s2 :schema o2 :output a2 :arglists} :- dschema/SchemaDesc]
  {:name (or n1 n2)
   :schema (or s1 s2)
   :output (or o1 o2)
   :arglists (merge a1 a2)})

(s/defn merge-descs :- dschema/SchemaDesc
  [desc1 :- (s/maybe dschema/SchemaDesc)
   desc2 :- (s/maybe dschema/SchemaDesc)]
  (apply merge-with combine-descs (remove nil? [desc1 desc2])))
