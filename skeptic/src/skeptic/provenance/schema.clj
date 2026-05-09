(ns skeptic.provenance.schema
  (:require [schema.core :as s]))

(s/defschema Source
  (s/enum :type-override :malli :schema :native :inferred))

(s/defschema Lang
  (s/conditional set?
                 #{(s/enum :clj :cljs)}
                 :else
                 (s/enum :clj :cljs)))

(s/defschema Provenance
  {:source        Source
   :qualified-sym (s/maybe s/Symbol)
   :declared-in   (s/maybe (s/cond-pre s/Symbol clojure.lang.Namespace))
   :var-meta      (s/maybe {s/Keyword s/Any})
   :refs          [(s/recursive #'Provenance)]
   :lang          Lang})
