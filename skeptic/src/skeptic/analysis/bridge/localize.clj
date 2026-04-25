(ns skeptic.analysis.bridge.localize
  (:require [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(def ^:dynamic *error-context*
  nil)

(defn compact-context-map
  [m]
  (into {}
        (remove (comp nil? val))
        m))

(defmacro with-error-context
  [context & body]
  `(binding [*error-context* (merge *error-context*
                                    (compact-context-map ~context))]
     ~@body))

(declare localize-value*)

(defn- localize-unbound-var
  [value seen-vars]
  (localize-value* (at/read-instance-field value "v") seen-vars))

(defn- localize-var
  [value seen-vars]
  (let [var-ref (or (sb/qualified-var-symbol value) value)]
    (cond
      (contains? seen-vars var-ref) (sb/placeholder-schema var-ref)
      (bound? value) (localize-value* @value (conj seen-vars var-ref))
      :else (sb/placeholder-schema var-ref))))

(defn- localize-schema-base-value
  [value seen-vars]
  (cond
    (sb/bottom-schema? value) sb/Bottom
    (sb/join? value)
    (apply sb/join (map #(localize-value* % seen-vars) (:schemas value)))
    (sb/valued-schema? value)
    (sb/valued-schema (localize-value* (:schema value) seen-vars)
                      (localize-value* (:value value) seen-vars))
    (sb/variable? value)
    (sb/variable (localize-value* (:schema value) seen-vars))
    :else value))


(defn- localize-raw-collection
  [value seen-vars]
  (cond
    (vector? value) (mapv #(localize-value* % seen-vars) value)
    (set? value) (into #{} (map #(localize-value* % seen-vars)) value)
    (and (map? value) (not (record? value)))
    (into {}
          (map (fn [[k v]]
                 [(localize-value* k seen-vars)
                  (localize-value* v seen-vars)]))
          value)
    (seq? value) (doall (map #(localize-value* % seen-vars) value))
    :else value))

(defn localize-value*
  [value seen-vars]
  (cond
    (nil? value) nil
    (at/same-class-name? value "clojure.lang.Var$Unbound")
    (localize-unbound-var value seen-vars)
    (instance? clojure.lang.Var value)
    (localize-var value seen-vars)
    (or (sb/bottom-schema? value)
        (sb/join? value)
        (sb/valued-schema? value)
        (sb/variable? value))
    (localize-schema-base-value value seen-vars)
    (at/semantic-type-value? value)
    value
    (or (vector? value)
        (set? value)
        (and (map? value) (not (record? value)))
        (seq? value))
    (localize-raw-collection value seen-vars)
    :else value))

(defn localize-value
  [value]
  (localize-value* value #{}))
