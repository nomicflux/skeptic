(ns skeptic.analysis.value
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn class->type
  [klass]
  (let [klass (sb/canonical-scalar-schema klass)]
    (cond
      (= klass s/Int) (at/->GroundT :int 'Int)
      (= klass s/Str) (at/->GroundT :str 'Str)
      (= klass s/Keyword) (at/->GroundT :keyword 'Keyword)
      (= klass s/Symbol) (at/->GroundT :symbol 'Symbol)
      (= klass s/Bool) (at/->GroundT :bool 'Bool)
      (and (class? klass)
           (not (or (= klass s/Any)
                    (= klass s/Num)
                    (= klass Number)
                    (= klass java.lang.Number)
                    (= klass Object)
                    (= klass java.lang.Object))))
      (at/->GroundT {:class klass} (abc/schema-explain klass))
      :else at/Dyn)))

(declare type-of-value type-join*)

(defn exact-runtime-value-type
  [value]
  (at/->ValueT (type-of-value value) value))

(defn collection-element-type
  [values]
  (if (seq values)
    (type-join* (map type-of-value values))
    at/Dyn))

(defn homogeneous-seq-type
  [constructor values]
  (constructor [(collection-element-type values)] true))

(defn map-value-type
  [m]
  (at/->MapT
   (into {}
         (map (fn [[k v]]
                [(exact-runtime-value-type k)
                 (exact-runtime-value-type v)]))
         m)))

(defn type-of-value
  [value]
  (cond
    (nil? value) (ato/exact-value-type nil)
    (or (integer? value)
        (string? value)
        (keyword? value)
        (symbol? value)
        (boolean? value)) (class->type (class value))
    (vector? value) (at/->VectorT (mapv type-of-value value) (= 1 (count value)))
    (or (list? value) (seq? value)) (homogeneous-seq-type at/->SeqT value)
    (set? value) (at/->SetT #{(collection-element-type value)} true)
    (map? value) (map-value-type value)
    (class? value) (class->type java.lang.Class)
    :else (class->type (class value))))

(defn type-join*
  [types]
  (let [types (vec (remove nil? (map ato/normalize-type types)))
        non-bottom (vec (remove at/bottom-type? types))]
    (cond
      (seq non-bottom) (ato/union-type non-bottom)
      (seq types) at/BottomType
      :else at/Dyn)))
