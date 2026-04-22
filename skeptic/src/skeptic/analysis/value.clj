(ns skeptic.analysis.value
  (:require [schema.core :as s]
            [skeptic.analysis.bridge.canonicalize :as abc]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn class->type
  [prov klass]
  (let [klass (sb/canonical-scalar-schema klass)]
    (cond
      (= klass s/Int) (at/->GroundT prov :int 'Int)
      (= klass s/Str) (at/->GroundT prov :str 'Str)
      (= klass s/Keyword) (at/->GroundT prov :keyword 'Keyword)
      (= klass s/Symbol) (at/->GroundT prov :symbol 'Symbol)
      (= klass s/Bool) (at/->GroundT prov :bool 'Bool)
      (or (= klass s/Num)
          (= klass Number)
          (= klass java.lang.Number))
      (at/NumericDyn prov)
      (and (class? klass)
           (not (or (= klass s/Any)
                    (= klass Object)
                    (= klass java.lang.Object))))
      (at/->GroundT prov {:class klass} (abc/schema-explain klass))
      :else (at/Dyn prov))))

(declare type-of-value type-join*)

(defn exact-runtime-value-type
  [prov value]
  (at/->ValueT prov (type-of-value prov value) value))

(defn collection-element-type
  [prov values]
  (if (seq values)
    (type-join* prov (map #(type-of-value prov %) values))
    (at/Dyn prov)))

(defn homogeneous-seq-type
  [prov constructor values]
  (constructor prov [(collection-element-type prov values)] true))

(defn map-value-type
  [prov m]
  (at/->MapT prov
             (into {}
                   (map (fn [[k v]]
                          [(exact-runtime-value-type prov k)
                           (exact-runtime-value-type prov v)]))
                   m)))

(defn type-of-value
  [prov value]
  (cond
    (nil? value) (ato/exact-value-type prov nil)
    (or (integer? value)
        (string? value)
        (keyword? value)
        (symbol? value)
        (boolean? value)) (class->type prov (class value))
    (vector? value) (at/->VectorT prov (mapv #(type-of-value prov %) value) (= 1 (count value)))
    (or (list? value) (seq? value)) (homogeneous-seq-type prov at/->SeqT value)
    (set? value) (at/->SetT prov #{(collection-element-type prov value)} true)
    (map? value) (map-value-type prov value)
    (class? value) (class->type prov java.lang.Class)
    :else (class->type prov (class value))))

(defn type-join*
  [prov types]
  (let [types (vec (remove nil? (map #(ato/normalize-type prov %) types)))
        non-bottom (vec (remove at/bottom-type? types))]
    (cond
      (seq non-bottom) (ato/union-type prov non-bottom)
      (seq types) (at/BottomType prov)
      :else (at/Dyn prov))))

(defn join
  "Join item types into a union. The result's provenance is `anchor-prov`
  (the container's); item provs stay on the items."
  [anchor-prov types]
  (type-join* anchor-prov types))
