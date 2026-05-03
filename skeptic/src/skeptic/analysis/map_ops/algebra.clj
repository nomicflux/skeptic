(ns skeptic.analysis.map-ops.algebra
  (:require [schema.core :as s]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]))

(defn- exact-key-for-literal
  [prov key-lit]
  (ato/exact-value-type prov key-lit))

(defn- entries-without-key
  [prov entries key-lit]
  (let [ev (exact-key-for-literal prov key-lit)]
    (into {}
          (remove (fn [[k _]]
                    (or (at/type=? k ev)
                        (and (at/optional-key-type? k)
                             (at/type=? (:inner k) ev))))
                  entries))))

(s/defn assoc-type :- ats/SemanticType
  [m-type :- ats/SemanticType key-lit :- s/Any value-type :- ats/SemanticType]
  (let [m-type (ato/normalize m-type)
        value-type (ato/normalize value-type)
        prov (ato/derive-prov m-type value-type)
        k (exact-key-for-literal prov key-lit)]
    (cond
      (at/maybe-type? m-type)
      (at/->MaybeT prov (assoc-type (:inner m-type) key-lit value-type))

      (at/map-type? m-type)
      (at/->MapT (prov/with-refs prov [(prov/of m-type) (prov/of k) (prov/of value-type)])
                 (assoc (entries-without-key prov (:entries m-type) key-lit)
                        k
                        value-type))

      :else
      (ato/dyn m-type value-type))))

(s/defn dissoc-type :- ats/SemanticType
  [m-type :- ats/SemanticType key-lit :- s/Any]
  (let [m-type (ato/normalize m-type)
        prov (ato/derive-prov m-type)]
    (cond
      (at/maybe-type? m-type)
      (at/->MaybeT prov (dissoc-type (:inner m-type) key-lit))

      (at/map-type? m-type)
      (let [removed-key (exact-key-for-literal prov key-lit)]
        (at/->MapT (prov/with-refs prov [(prov/of m-type) (prov/of removed-key)])
                   (entries-without-key prov (:entries m-type) key-lit)))

      :else
      (ato/dyn m-type))))

(defn- first-unary-output-type
  [fn-type]
  (when (at/fun-type? fn-type)
    (some (fn [m]
            (when (seq (:inputs m))
              (:output m)))
          (:methods fn-type))))

(s/defn update-type :- ats/SemanticType
  [m-type :- ats/SemanticType key-lit :- s/Any fn-type :- ats/SemanticType]
  (let [m-type (ato/normalize m-type)
        fn-type (ato/normalize fn-type)
        out (or (first-unary-output-type fn-type) (ato/dyn fn-type))]
    (assoc-type m-type key-lit out)))

(s/defn merge-types :- ats/SemanticType
  [anchor-prov :- provs/Provenance types :- [ats/SemanticType]]
  (let [types (mapv ato/normalize types)]
    (cond
      (empty? types) (at/Dyn anchor-prov)
      (every? at/map-type? types) (amo/merge-map-types anchor-prov types)
      :else (apply ato/dyn types))))
