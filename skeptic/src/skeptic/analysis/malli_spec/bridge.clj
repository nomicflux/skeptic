(ns skeptic.analysis.malli-spec.bridge
  (:require [malli.core :as m]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn- invalid-malli-spec-input
  [value e]
  (throw (IllegalArgumentException.
          (format "Expected Malli spec value: %s" (pr-str value))
          e)))

(defn admit-malli-spec
  "Validate a value as a Malli spec and return its canonical form."
  [value]
  (try
    (m/form (m/schema value))
    (catch Exception e
      (invalid-malli-spec-input value e))))

(defn malli-spec-domain?
  [value]
  (try
    (some? (admit-malli-spec value))
    (catch IllegalArgumentException _e
      false)))

(defn- malli-leaf->type
  "Convert a Malli leaf value to a semantic type."
  [leaf]
  (cond
    (= leaf :int) (at/->GroundT :int 'Int)
    (= leaf :string) (at/->GroundT :str 'Str)
    (= leaf :keyword) (at/->GroundT :keyword 'Keyword)
    (= leaf :boolean) (at/->GroundT :bool 'Bool)
    (= leaf :any) at/Dyn
    :else at/Dyn))

(defn- function-shape?
  "Check if canonical form is [:=> [:cat & inputs] output]."
  [form]
  (and (vector? form)
       (= 3 (count form))
       (= :=> (first form))
       (vector? (second form))
       (= :cat (first (second form)))))

(defn- maybe-shape?
  [form]
  (and (vector? form) (= :maybe (first form))))

(defn- or-shape?
  [form]
  (and (vector? form) (= :or (first form))))

(defn- form->type
  [form]
  (cond
    (function-shape? form)
    (let [inputs (rest (second form))
          output (nth form 2)]
      (at/->FunT [(at/->FnMethodT (mapv form->type inputs)
                                  (form->type output)
                                  (count inputs)
                                  false)]))
    (maybe-shape? form) (at/->MaybeT (form->type (second form)))
    (or-shape? form) (ato/union-type (mapv form->type (rest form)))
    :else (malli-leaf->type form)))

(defn malli-spec->type
  "Convert a Malli spec to a semantic type."
  [value]
  (form->type (admit-malli-spec value)))
