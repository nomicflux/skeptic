(ns skeptic.analysis.annotate.numeric
  (:require [schema.core :as s]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.provenance.schema :as provs]))

(s/defn bool-type :- ats/SemanticType
  [prov :- provs/Provenance]
  (at/->GroundT prov :bool 'Bool))

(def integral-arg-classes
  #{Long Integer Short Byte java.math.BigInteger clojure.lang.BigInt})

(defn- numeric-ground-class
  [type]
  (let [ground (:ground (ato/normalize type))]
    (when (and (map? ground) (:class ground))
      (:class ground))))

(s/defn integral-type? :- s/Bool
  [type :- ats/SemanticType]
  (let [type (ato/normalize type)]
    (cond
      (and (at/ground-type? type) (= :int (:ground type))) true
      (and (at/ground-type? type) (map? (:ground type)) (:class (:ground type)))
      (contains? integral-arg-classes (:class (:ground type)))
      (and (at/value-type? type) (integer? (:value type))) true
      (at/refinement-type? type) (integral-type? (:base type))
      (at/intersection-type? type) (every? integral-type? (:members type))
      :else false)))

(def integral-ground-type? integral-type?)

(s/defn numeric-type? :- s/Bool
  [type :- ats/SemanticType]
  (let [type (ato/normalize type)]
    (cond
      (integral-type? type) true
      (at/numeric-dyn-type? type) true
      (and (at/ground-type? type) (numeric-ground-class type)) true
      (and (at/value-type? type) (number? (:value type))) true
      (at/refinement-type? type) (numeric-type? (:base type))
      (at/intersection-type? type) (every? numeric-type? (:members type))
      :else false)))

(s/defn non-int-numeric-type? :- s/Bool
  [type :- ats/SemanticType]
  (let [type (ato/normalize type)
        klass (numeric-ground-class type)]
    (cond
      (and (at/value-type? type) (number? (:value type)) (not (integer? (:value type))))
      true

      (and (at/ground-type? type) klass)
      (not (contains? integral-arg-classes klass))

      (at/refinement-type? type)
      (non-int-numeric-type? (:base type))

      (at/intersection-type? type)
      (every? non-int-numeric-type? (:members type))

      :else false)))

(defn- numeric-ground-output-type
  [type]
  (let [type (ato/normalize type)
        prov (ato/derive-prov type)]
    (cond
      (at/value-type? type)
      (or (:inner type) (at/NumericDyn prov))

      (and (at/ground-type? type) (numeric-ground-class type))
      type

      (at/refinement-type? type)
      (numeric-ground-output-type (:base type))

      :else nil)))

(s/defn inc-dec-output-type :- (s/maybe ats/SemanticType)
  [arg-type :- ats/SemanticType]
  (let [prov (ato/derive-prov arg-type)]
    (cond
      (integral-type? arg-type) (at/->GroundT prov :int 'Int)
      (non-int-numeric-type? arg-type) (or (numeric-ground-output-type arg-type) (at/NumericDyn prov))
      (numeric-type? arg-type) (at/NumericDyn prov)
      :else nil)))

(s/defn binary-integral-locals-narrow? :- s/Bool
  [arg-nodes :- [s/Any], arg-types :- [ats/SemanticType]]
  (and (= 2 (count arg-nodes))
       (not= :const (:op (first arg-nodes)))
       (not= :const (:op (second arg-nodes)))
       (integral-type? (first arg-types))
       (integral-type? (second arg-types))))

(s/defn invoke-integral-math-narrow-type :- (s/maybe ats/SemanticType)
  [anchor-prov :- provs/Provenance, call-sym :- s/Any, args :- [s/Any], actual-argtypes :- [ats/SemanticType]]
  (let [arity (count args)]
    (cond
      (and (contains? ac/inc-invoke-syms call-sym) (= 1 arity)
           (numeric-type? (first actual-argtypes)))
      (inc-dec-output-type (first actual-argtypes))

      (and (or (contains? ac/plus-invoke-syms call-sym)
               (contains? ac/multiply-invoke-syms call-sym))
           (seq args)
           (every? #(not= :const (:op %)) args)
           (every? integral-type? actual-argtypes))
      (at/->GroundT anchor-prov :int 'Int)

      (and (contains? ac/minus-invoke-syms call-sym) (= 1 arity)
           (not= :const (:op (first args)))
           (integral-type? (first actual-argtypes)))
      (at/->GroundT anchor-prov :int 'Int)

      (and (contains? ac/minus-invoke-syms call-sym) (= 2 arity)
           (binary-integral-locals-narrow? args actual-argtypes))
      (at/->GroundT anchor-prov :int 'Int)
      :else nil)))

(s/defn narrow-static-numbers-output :- (s/maybe ats/SemanticType)
  [anchor-prov :- provs/Provenance, node :- s/Any, args :- [s/Any], actual-argtypes :- [ats/SemanticType], native-info :- s/Any]
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
                 (not= :const (:op (first args)))
                 (integral-type? (first actual-argtypes)))
            (at/->GroundT anchor-prov :int 'Int)

            (binary-integral-locals-narrow? args actual-argtypes)
            (at/->GroundT anchor-prov :int 'Int)
            :else nil))
        (:output-type native-info))))
