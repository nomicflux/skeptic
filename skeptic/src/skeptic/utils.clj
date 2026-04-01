(ns skeptic.utils
  (:require
   [skeptic.schema :as dschema]
   [schema.core :as s]))

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
