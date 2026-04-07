(ns skeptic.analysis.map-ops.algebra
  (:require [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn- exact-key-for-literal
  [key-lit]
  (ato/exact-value-type key-lit))

(defn- entries-without-key
  [entries key-lit]
  (let [ev (exact-key-for-literal key-lit)]
    (into {}
          (remove (fn [[k _]]
                    (or (at/type-equal? k ev)
                        (and (at/optional-key-type? k)
                             (at/type-equal? (:inner k) ev))))
                  entries))))

(defn assoc-type
  [m-type key-lit value-type]
  (let [m-type (ato/normalize-type m-type)
        value-type (ato/normalize-type value-type)
        k (exact-key-for-literal key-lit)]
    (cond
      (at/maybe-type? m-type)
      (at/->MaybeT (assoc-type (:inner m-type) key-lit value-type))

      (at/map-type? m-type)
      (at/->MapT (assoc (entries-without-key (:entries m-type) key-lit)
                  k
                  value-type))

      :else
      at/Dyn)))

(defn dissoc-type
  [m-type key-lit]
  (let [m-type (ato/normalize-type m-type)]
    (cond
      (at/maybe-type? m-type)
      (at/->MaybeT (dissoc-type (:inner m-type) key-lit))

      (at/map-type? m-type)
      (at/->MapT (entries-without-key (:entries m-type) key-lit))

      :else
      at/Dyn)))

(defn- first-unary-output-type
  [fn-type]
  (when (at/fun-type? fn-type)
    (some (fn [m]
            (when (seq (:inputs m))
              (:output m)))
          (:methods fn-type))))

(defn update-type
  [m-type key-lit fn-type]
  (let [m-type (ato/normalize-type m-type)
        fn-type (ato/normalize-type fn-type)
        out (or (first-unary-output-type fn-type) at/Dyn)]
    (assoc-type m-type key-lit out)))

(defn merge-types
  [types]
  (let [types (mapv ato/normalize-type types)]
    (if (every? at/map-type? types)
      (amo/merge-map-types types)
      at/Dyn)))
