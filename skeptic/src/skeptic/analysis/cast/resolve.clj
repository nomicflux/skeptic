(ns skeptic.analysis.cast.resolve
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as bridge]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def ^:dynamic *resolve-active* #{})
(def ^:dynamic *specialization-resolve-active* #{})

(def recursive-specialization ::recursive-specialization)

(defn- lookup-var
  [ref]
  (if (var? ref) ref (some-> ref resolve)))

(defn- deref-bound
  [v]
  (when (and v (bound? v)) @v))

(s/defn resolve-named :- (s/maybe at/SemanticType)
  "Resolve a PlaceholderT or InfCycleT to its bridge-imported Type, or nil
   on cycle (this ref currently being resolved) or unbound var."
  [t :- at/SemanticType]
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

(s/defn resolve-specialization :- s/Any
  [t :- at/SemanticType]
  (let [ref (:ref t)]
    (cond
      (*specialization-resolve-active* ref)
      recursive-specialization

      (at/specialization-ref-resolved t)
      (at/specialization-ref-resolved t)

      :else
      (throw (ex-info "Unresolved local function specialization reference"
                      {:ref ref
                       :type t})))))

(defn with-specialization-active
  [ref f]
  (binding [*specialization-resolve-active* (conj *specialization-resolve-active* ref)]
    (f)))
