(ns skeptic.analysis.malli-spec.bridge
  (:require [malli.core :as m]
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

(defn malli-spec->type
  "Stub: admit the value, then return the broad Dyn Type."
  [value]
  (admit-malli-spec value)
  at/Dyn)
