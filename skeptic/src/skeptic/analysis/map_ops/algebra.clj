(ns skeptic.analysis.map-ops.algebra
  (:require [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn- exact-key-for-literal
  [prov key-lit]
  (ato/exact-value-type prov key-lit))

(defn- entries-without-key
  [prov entries key-lit]
  (let [ev (exact-key-for-literal prov key-lit)]
    (into {}
          (remove (fn [[k _]]
                    (or (at/type-equal? k ev)
                        (and (at/optional-key-type? k)
                             (at/type-equal? (:inner k) ev))))
                  entries))))

(defn assoc-type
  [m-type key-lit value-type]
  (let [m-type (ato/normalize m-type)
        value-type (ato/normalize value-type)
        prov (ato/derive-prov m-type value-type)
        k (exact-key-for-literal prov key-lit)]
    (cond
      (at/maybe-type? m-type)
      (at/->MaybeT prov (assoc-type (:inner m-type) key-lit value-type))

      (at/map-type? m-type)
      (at/->MapT prov (assoc (entries-without-key prov (:entries m-type) key-lit)
                       k
                       value-type))

      :else
      (ato/dyn m-type value-type))))

(defn dissoc-type
  [m-type key-lit]
  (let [m-type (ato/normalize m-type)
        prov (ato/derive-prov m-type)]
    (cond
      (at/maybe-type? m-type)
      (at/->MaybeT prov (dissoc-type (:inner m-type) key-lit))

      (at/map-type? m-type)
      (at/->MapT prov (entries-without-key prov (:entries m-type) key-lit))

      :else
      (ato/dyn m-type))))

(defn- first-unary-output-type
  [fn-type]
  (when (at/fun-type? fn-type)
    (some (fn [m]
            (when (seq (:inputs m))
              (:output m)))
          (:methods fn-type))))

(defn update-type
  [m-type key-lit fn-type]
  (let [m-type (ato/normalize m-type)
        fn-type (ato/normalize fn-type)
        out (or (first-unary-output-type fn-type) (ato/dyn fn-type))]
    (assoc-type m-type key-lit out)))

(defn merge-types
  [types]
  (let [types (mapv ato/normalize types)]
    (if (every? at/map-type? types)
      (amo/merge-map-types types)
      (apply ato/dyn types))))
