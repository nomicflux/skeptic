(ns skeptic.analysis.call-kinds.numeric
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.call-kinds.symbols :as symbols]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance.schema :as provs]))

(s/defn ^:private integral-type? :- s/Bool
  [type :- at/SemanticType]
  (let [type (ato/normalize type)]
    (cond
      (and (at/ground-type? type) (= :int (:ground type))) true
      (and (at/ground-type? type) (map? (:ground type)) (:class (:ground type)))
      (at/class-integral? (:class (:ground type)))
      (and (at/value-type? type) (integer? (:value type))) true
      (at/refinement-type? type) (integral-type? (:base type))
      (at/intersection-type? type) (every? integral-type? (:members type))
      :else false)))

(s/defn ^:private numeric-type? :- s/Bool
  [type :- at/SemanticType]
  (let [type (ato/normalize type)]
    (cond
      (integral-type? type) true
      (at/numeric-dyn-type? type) true
      (and (at/ground-type? type) (= :double (:ground type))) true
      (and (at/ground-type? type) (= :float (:ground type))) true
      (and (at/ground-type? type) (numeric/numeric-ground-handle type)) true
      (and (at/value-type? type) (number? (:value type))) true
      (at/refinement-type? type) (numeric-type? (:base type))
      (at/intersection-type? type) (every? numeric-type? (:members type))
      :else false)))

(defn- numeric-ground-output-type
  [type]
  (let [type (ato/normalize type)
        prov (ato/derive-prov type)]
    (cond
      (at/value-type? type)
      (or (:inner type) (at/NumericDyn prov))

      (and (at/ground-type? type) (= :double (:ground type)))
      type

      (and (at/ground-type? type) (= :float (:ground type)))
      type

      (and (at/ground-type? type) (numeric/numeric-ground-handle type))
      type

      (at/refinement-type? type)
      (numeric-ground-output-type (:base type))

      :else nil)))

(s/defn ^:private inc-dec-output-type :- (s/maybe at/SemanticType)
  [arg-type :- at/SemanticType]
  (let [prov (ato/derive-prov arg-type)]
    (cond
      (integral-type? arg-type) (at/->GroundT prov :int 'Int)
      (numeric/non-int-numeric-type? arg-type) (or (numeric-ground-output-type arg-type) (at/NumericDyn prov))
      (numeric-type? arg-type) (at/NumericDyn prov)
      :else nil)))

(s/defn ^:private binary-integral-locals-narrow? :- s/Bool
  [arg-nodes :- [s/Any], arg-types :- [at/SemanticType]]
  (and (= 2 (count arg-nodes))
       (not (aapi/const-node? (first arg-nodes)))
       (not (aapi/const-node? (second arg-nodes)))
       (integral-type? (first arg-types))
       (integral-type? (second arg-types))))

(s/defn invoke-numeric-narrow-type :- (s/maybe at/SemanticType)
  [anchor-prov :- provs/Provenance, fn-node :- s/Any, args :- [s/Any], actual-argtypes :- [at/SemanticType]]
  (let [arity (count args)]
    (cond
      (and (symbols/inc? fn-node) (= 1 arity)
           (numeric-type? (first actual-argtypes)))
      (inc-dec-output-type (first actual-argtypes))

      (and (or (symbols/plus? fn-node)
               (symbols/multiply? fn-node))
           (seq args)
           (not-any? aapi/const-node? args)
           (every? integral-type? actual-argtypes))
      (at/->GroundT anchor-prov :int 'Int)

      (and (symbols/minus? fn-node) (= 1 arity)
           (not (aapi/const-node? (first args)))
           (integral-type? (first actual-argtypes)))
      (at/->GroundT anchor-prov :int 'Int)

      (and (symbols/minus? fn-node) (= 2 arity)
           (binary-integral-locals-narrow? args actual-argtypes))
      (at/->GroundT anchor-prov :int 'Int)
      :else nil)))

(s/defn static-numeric-narrow-type :- (s/maybe at/SemanticType)
  [anchor-prov :- provs/Provenance, node :- s/Any, args :- [s/Any], actual-argtypes :- [at/SemanticType], native-info :- s/Any]
  (let [method (:method node)]
    (or (when (#{'inc 'dec} method)
          (when (and (= 1 (count args))
                     (numeric-type? (first actual-argtypes)))
            (inc-dec-output-type (first actual-argtypes))))
        (when (#{'add 'multiply} method)
          (when (binary-integral-locals-narrow? args actual-argtypes)
            (at/->GroundT anchor-prov :int 'Int)))
        (when (= 'minus method)
          (cond
            (and (= 1 (count args))
                 (not (aapi/const-node? (first args)))
                 (integral-type? (first actual-argtypes)))
            (at/->GroundT anchor-prov :int 'Int)

            (binary-integral-locals-narrow? args actual-argtypes)
            (at/->GroundT anchor-prov :int 'Int)
            :else nil))
        (:output-type native-info))))
