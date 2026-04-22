(ns skeptic.analysis.annotate.numeric
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn bool-type
  [prov]
  (at/->GroundT prov :bool 'Bool))

(def integral-arg-classes
  #{Long Integer Short Byte java.math.BigInteger clojure.lang.BigInt})

(defn- numeric-ground-class
  [type]
  (let [ground (:ground (ato/normalize type))]
    (when (and (map? ground) (:class ground))
      (:class ground))))

(defn integral-type?
  [type]
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

(defn numeric-type?
  [type]
  (let [type (ato/normalize type)]
    (cond
      (integral-type? type) true
      (at/numeric-dyn-type? type) true
      (and (at/ground-type? type) (numeric-ground-class type)) true
      (and (at/value-type? type) (number? (:value type))) true
      (at/refinement-type? type) (numeric-type? (:base type))
      (at/intersection-type? type) (every? numeric-type? (:members type))
      :else false)))

(defn non-int-numeric-type?
  [type]
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

(defn inc-dec-output-type
  [arg-type]
  (let [prov (ato/derive-prov arg-type)]
    (cond
      (integral-type? arg-type) (at/->GroundT prov :int 'Int)
      (non-int-numeric-type? arg-type) (or (numeric-ground-output-type arg-type) (at/NumericDyn prov))
      (numeric-type? arg-type) (at/NumericDyn prov)
      :else nil)))

(defn binary-integral-locals-narrow?
  [arg-nodes arg-types]
  (and (= 2 (count arg-nodes))
       (not= :const (:op (first arg-nodes)))
       (not= :const (:op (second arg-nodes)))
       (integral-type? (first arg-types))
       (integral-type? (second arg-types))))

(defn invoke-integral-math-narrow-type
  [anchor-prov fn-node args actual-argtypes]
  (cond
    (and (ac/inc-invoke? fn-node) (= 1 (count args))
         (numeric-type? (first actual-argtypes)))
    (inc-dec-output-type (first actual-argtypes))

    (and (or (ac/plus-invoke? fn-node) (ac/multiply-invoke? fn-node))
         (seq args)
         (every? #(not= :const (:op %)) args)
         (every? integral-type? actual-argtypes))
    (at/->GroundT anchor-prov :int 'Int)

    (and (ac/minus-invoke? fn-node) (= 1 (count args))
         (not= :const (:op (first args)))
         (integral-type? (first actual-argtypes)))
    (at/->GroundT anchor-prov :int 'Int)

    (and (ac/minus-invoke? fn-node) (= 2 (count args))
         (binary-integral-locals-narrow? args actual-argtypes))
    (at/->GroundT anchor-prov :int 'Int)
    :else nil))

(defn narrow-static-numbers-output
  [anchor-prov node args actual-argtypes native-info]
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
