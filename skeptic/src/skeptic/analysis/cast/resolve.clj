(ns skeptic.analysis.cast.resolve
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as bridge]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance :as prov]))

(def ^:dynamic *resolve-active* #{})

(defn- lookup-var
  [ref]
  (if (var? ref) ref (some-> ref resolve)))

(defn- deref-bound
  [v]
  (when (and v (bound? v)) @v))

(s/defn resolve-named :- (s/maybe ats/SemanticType)
  "Resolve a PlaceholderT or InfCycleT to its bridge-imported Type, or nil
   on cycle (this ref currently being resolved) or unbound var."
  [t :- ats/SemanticType]
  (let [ref (:ref t)]
    (when-not (*resolve-active* ref)
      (some->> (lookup-var ref)
               deref-bound
               (bridge/import-schema-type (prov/of t))))))

(defn with-active
  "Call f with ref added to *resolve-active* for the duration."
  [ref f]
  (binding [*resolve-active* (conj *resolve-active* ref)]
    (f)))
