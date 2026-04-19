(ns skeptic.test-examples.basics
  (:require [schema.core :as s]))

(s/defschema PosInt
  (s/constrained s/Int pos?))

(s/defn int-add :- s/Int
  ([x :- s/Int]
   x)
  ([x :- s/Int
    y :- s/Int]
   (+ x y))
  ([x :- s/Int
    y :- s/Int
    & zs :- [s/Int]]
   (reduce + (+ x y) zs)))

(s/defn sample-arg-annotated-fn
  [x :- s/Int]
  (int-add 1 (int-add 2 x)))

(s/defn sample-fully-annotated-fn :- s/Int
  [x :- s/Int]
  (int-add 1 (int-add 2 x)))

(defn sample-unannotated-fn
  [x]
  (int-add 1 (int-add 2 x)))

(s/defn sample-annotated-bad-fn :- s/Int
  [x :- s/Int]
  (int-add 1 (int-add nil x)))

(s/defn sample-bad-annotation-fn :- s/Int
  [not-an-int :- s/Str]
  (int-add not-an-int 2))

(s/defn takes-named-int :- s/Int
  [x :- (s/named s/Int 'age)]
  x)

(defn sample-named-input-fn
  []
  (takes-named-int 1))

(s/defn sample-named-output-fn :- (s/named s/Int 'age)
  [x :- s/Int]
  x)

(s/defn sample-constrained-output-fn :- PosInt
  [x :- s/Int]
  x)

(s/defn sample-bad-constrained-output-fn :- PosInt
  [x :- s/Str]
  x)

(defn sample-direct-nil-arg-fn
  [x]
  (int-add 1 (int-add nil x)))

(defn sample-mismatched-types
  [x]
  (int-add x "hi"))

(defn sample-str-fn
  [x]
  (str x)
  (int-add 1 (str x))
  (let [y (str nil)]
    (int-add 1 y)))

(s/defn fn-chain-success :- s/Int
  [f :- (s/=> s/Int s/Int)
   g :- (s/=> s/Int s/Int)
   x :- s/Int]
  (g (f x)))

(s/defn fn-chain-failure :- s/Int
  [f :- (s/=> s/Str s/Int)
   g :- (s/=> s/Int s/Int)
   x :- s/Int]
  (g (f x)))

(defn sample-bad-parametric-fn
  [x]
  (let [f (fn [_y] nil)
        g (fn [f] (f x))]
    (+ 1 (g f))))

(defn sample-multi-arity-fn
  ([x]
   (int-add x nil)
   (sample-multi-arity-fn x nil))
  ([x y]
   (int-add x y nil)
   (sample-multi-arity-fn x y nil))
  ([x y & z]
   (int-add x y z nil)
   (apply str x y z)))

(s/defn regex-return-caller :- #"^[a-z]+$"
  []
  "x")

(s/defn inc-int-success :- s/Int
  [x :- s/Int]
  (inc x))

(s/defn inc-num-success :- s/Num
  [x :- s/Num]
  (inc x))

(s/defn inc-double-success :- java.lang.Double
  [x :- java.lang.Double]
  (inc x))

(s/defn process-map
  [m :- {:route s/Str :record {:id s/Uuid :name s/Str}}]
  m)

(s/defn closure-param-type-fn-success
  [a :- s/Any
   b :- (s/=> s/Any s/Any)
   c :- s/Any]
  (fn [x y z]
    (process-map {:route "start"
                  :record y})))
