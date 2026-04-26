(ns skeptic.test-examples.resolution
  (:require [schema.core :as s]
            [skeptic.test-examples.basics :refer [int-add]]))

(defn sample-namespaced-keyword-fn
  [x]
  (let [y {::key1 1
           ::s/key2 2}]
    (int-add x (::s/key2 y))
    (int-add x (::key1 y))))

(defn sample-fn-fn
  [x]
  ((if x int-add -) 1 2))

(defn sample-var-fn-fn
  [x]
  ((if x #'int-add #'-) 1 2))

(defn sample-found-var-fn-fn
  [x]
  ((if x #'int-add -) 1 2))

(defn sample-missing-var-fn-fn
  [x]
  ((if x int-add #'-) 1 2))

(defn sample-let-fn-fn
  [x]
  (let [f (fn [y] (int-add y 1))]
    (f x)))

(defn sample-let-fn-bad1-fn
  [x]
  (let [f (fn [y] (int-add y nil))]
    (f x)))

(defn sample-let-fn-bad2-fn
  [x]
  (let [f (fn [y] (int-add y x))]
    (f nil)))

(defn sample-functional-fn
  [x]
  (let [f (fn [y] (int-add y 1))
        g (fn [f] (f x))]
    (g f)))

(s/defn flat-multi-step-takes-str :- s/Str
  [x :- s/Str]
  x)

(s/defn flat-multi-step-takes-int :- s/Int
  [x :- s/Int]
  x)

(s/defn flat-multi-step-f :- s/Int
  []
  (int-add 1 2))

(s/defn flat-multi-step-g :- s/Int
  []
  (flat-multi-step-f))

(defn flat-multi-step-failure
  []
  (flat-multi-step-takes-str (flat-multi-step-g)))

(defn flat-multi-step-success
  []
  (flat-multi-step-takes-int (flat-multi-step-g)))

(defn unannotated-local-helper-f
  []
  1)

(defn unannotated-local-helper-g
  []
  (unannotated-local-helper-f))

(declare forward-declared-target
         mutual-recursive-left
         mutual-recursive-right)

(defn forward-declared-caller
  [x]
  (forward-declared-target x))

(defn forward-declared-target
  [x]
  x)

(defn self-recursive-identity
  [x]
  (if x
    x
    (self-recursive-identity x)))

(defn mutual-recursive-left
  [x]
  (if x
    x
    (mutual-recursive-right x)))

(defn mutual-recursive-right
  [x]
  (if x
    x
    (mutual-recursive-left x)))

(defn sample-bigdecimal-method-value-fn
  []
  (let [a (BigDecimal. "1")
        b (BigDecimal. "2")
        x (BigDecimal/.equals a b)]
    x))
