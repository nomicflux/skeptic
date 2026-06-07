(ns skeptic.analysis.annotate.numeric
  (:require [schema.core :as s]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance.schema :as provs]))

(s/defn bool-type :- at/SemanticType
  [prov :- provs/Provenance]
  (at/->GroundT prov :bool 'Bool))

(defn numeric-ground-handle
  [type]
  (let [ground (:ground (ato/normalize type))]
    (when (and (map? ground) (:class ground))
      (:class ground))))

(s/defn non-int-numeric-type? :- s/Bool
  [type :- at/SemanticType]
  (let [type (ato/normalize type)
        klass (numeric-ground-handle type)]
    (cond
      (and (at/value-type? type) (number? (:value type)) (not (integer? (:value type))))
      true

      (and (at/ground-type? type) (= :double (:ground type)))
      true

      (and (at/ground-type? type) (= :float (:ground type)))
      true

      (and (at/ground-type? type) klass)
      (not (at/class-integral? klass))

      (at/refinement-type? type)
      (non-int-numeric-type? (:base type))

      (at/intersection-type? type)
      (every? non-int-numeric-type? (:members type))

      :else false)))
