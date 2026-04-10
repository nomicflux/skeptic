(ns skeptic.analysis.annotate.numeric
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def bool-type
  (at/->GroundT :bool 'Bool))

(def integral-arg-classes
  #{Long Integer Short Byte java.math.BigInteger clojure.lang.BigInt})

(defn integral-ground-type?
  [type]
  (let [type (ato/normalize-type type)]
    (cond
      (and (at/ground-type? type) (= :int (:ground type))) true
      (and (at/ground-type? type) (map? (:ground type)) (:class (:ground type)))
      (contains? integral-arg-classes (:class (:ground type)))
      (and (at/value-type? type) (integer? (:value type))) true
      (at/refinement-type? type) (integral-ground-type? (:base type))
      (at/intersection-type? type) (every? integral-ground-type? (:members type))
      :else false)))

(defn inc-dec-narrow-int-output?
  [arg-node arg-type]
  (and (not= :const (:op arg-node)) (integral-ground-type? arg-type)))

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
  (let [method (:method node)]
    (or (when (#{'inc 'dec} method)
          (when (and (= 1 (count args))
                     (inc-dec-narrow-int-output? (first args) (first actual-argtypes)))
            (at/->GroundT :int 'Int)))
        (when (#{'add 'multiply} method)
          (when (binary-integral-locals-narrow? args actual-argtypes)
            (at/->GroundT :int 'Int)))
        (when (= 'minus method)
          (cond
            (and (= 1 (count args))
                 (not= :const (:op (first args)))
                 (integral-ground-type? (first actual-argtypes)))
            (at/->GroundT :int 'Int)

            (binary-integral-locals-narrow? args actual-argtypes)
            (at/->GroundT :int 'Int)
            :else nil))
        (:output-type native-info))))
