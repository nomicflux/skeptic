(ns skeptic.test-helpers
  (:require
   [skeptic.analysis.bridge :as ab]
   [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'test-sym 'skeptic.test nil))

(defn T [schema] (ab/schema->type tp schema))
