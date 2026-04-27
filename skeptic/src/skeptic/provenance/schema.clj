(ns skeptic.provenance.schema
  (:require [schema.core :as s]))

(s/defschema Source
  (s/enum :type-override :malli :schema :native :inferred))

(s/defschema Provenance
  {:source        Source
   :qualified-sym (s/maybe s/Symbol)
   :declared-in   (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
   :var-meta      (s/maybe {s/Keyword s/Any})
   :refs          [(s/recursive #'Provenance)]})
