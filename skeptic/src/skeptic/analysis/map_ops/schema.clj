(ns skeptic.analysis.map-ops.schema
  (:require [schema.core :as s]
            [skeptic.provenance.schema :as provs]))

(s/defschema ExactKeyQuery
  {:skeptic.analysis.map-ops/map-key-query (s/eq true)
   :kind (s/eq :exact)
   :prov provs/Provenance
   :value s/Any
   :source-form s/Any})
