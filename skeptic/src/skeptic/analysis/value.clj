(ns skeptic.analysis.value
  (:require [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(defn class->schema
  [klass]
  (sb/canonical-scalar-schema klass))

(declare schema-of-value)

(defn coll-element-schema
  [values]
  (if (seq values)
    (abc/schema-join (set (map schema-of-value values)))
    s/Any))

(defn map-schema
  [m]
  (abc/canonicalize-schema
   (into {}
         (map (fn [[k v]]
                [(sb/valued-schema (schema-of-value k) k)
                 (sb/valued-schema (schema-of-value v) v)]))
         m)))

(defn schema-of-value
  [value]
  (cond
    (nil? value) (s/maybe s/Any)
    (integer? value) s/Int
    (string? value) s/Str
    (keyword? value) s/Keyword
    (symbol? value) s/Symbol
    (boolean? value) s/Bool
    (vector? value) (mapv schema-of-value value)
    (or (list? value) (seq? value)) [(coll-element-schema value)]
    (set? value) #{(coll-element-schema value)}
    (map? value) (map-schema value)
    (class? value) java.lang.Class
    :else (class->schema (class value))))

(defn type-of-value
  [value]
  (an/normalize-declared-type (schema-of-value value)))

(defn type-join*
  [types]
  (let [types (vec (remove nil? (map ab/normalize-type types)))
        non-bottom (vec (remove at/bottom-type? types))]
    (cond
      (seq non-bottom) (ab/union-type non-bottom)
      (seq types) at/BottomType
      :else at/Dyn)))
