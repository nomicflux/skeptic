(ns skeptic.analysis.annotate.numeric
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def bool-type
  (at/->GroundT :bool 'Bool))

(def integral-arg-classes
  #{Long Integer Short Byte java.math.BigInteger clojure.lang.BigInt})

(defn integral-ground-type?
  [t]
  (let [t (ato/normalize-type t)]
    (cond
      (and (at/ground-type? t) (= :int (:ground t))) true
      (and (at/ground-type? t) (map? (:ground t)) (:class (:ground t)))
      (contains? integral-arg-classes (:class (:ground t)))
      (and (at/value-type? t) (integer? (:value t))) true
      :else false)))

(defn inc-dec-narrow-int-output?
  [arg-node arg-type]
  (and (not= :const (:op arg-node))
       (integral-ground-type? arg-type)))

(defn binary-integral-locals-narrow?
  [arg-nodes arg-types]
  (and (= 2 (count arg-nodes))
       (not= :const (:op (first arg-nodes)))
       (not= :const (:op (second arg-nodes)))
       (integral-ground-type? (first arg-types))
       (integral-ground-type? (second arg-types))))

(defn invoke-integral-math-narrow-type
  [fn-node args actual-argtypes]
  (cond
    (and (ac/inc-invoke? fn-node) (= 1 (count args))
         (inc-dec-narrow-int-output? (first args) (first actual-argtypes)))
    (at/->GroundT :int 'Int)

    (and (or (ac/plus-invoke? fn-node) (ac/multiply-invoke? fn-node))
         (seq args)
         (every? #(not= :const (:op %)) args)
         (every? integral-ground-type? actual-argtypes))
    (at/->GroundT :int 'Int)

    (and (ac/minus-invoke? fn-node) (= 1 (count args))
         (not= :const (:op (first args)))
         (integral-ground-type? (first actual-argtypes)))
    (at/->GroundT :int 'Int)

    (and (ac/minus-invoke? fn-node) (= 2 (count args))
         (binary-integral-locals-narrow? args actual-argtypes))
    (at/->GroundT :int 'Int)

    :else nil))

(defn narrow-static-numbers-output
  [node args actual-argtypes native-info]
  (let [out (:output-type native-info)
        m (:method node)]
    (or (when (#{'inc 'dec} m)
          (when (and (= 1 (count args))
                     (inc-dec-narrow-int-output? (first args) (first actual-argtypes)))
            (at/->GroundT :int 'Int)))
        (when (#{'add 'multiply} m)
          (when (binary-integral-locals-narrow? args actual-argtypes)
            (at/->GroundT :int 'Int)))
        (when (= 'minus m)
          (cond
            (and (= 1 (count args))
                 (not= :const (:op (first args)))
                 (integral-ground-type? (first actual-argtypes)))
            (at/->GroundT :int 'Int)
            (binary-integral-locals-narrow? args actual-argtypes)
            (at/->GroundT :int 'Int)
            :else nil))
        out)))
