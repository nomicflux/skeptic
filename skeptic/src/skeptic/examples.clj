(ns skeptic.examples
  (:require [schema.core :as s]))

(def m 1)

(s/def n :- s/Int 2)

(defn with-let-not-schema
  [x]
  (let [y (* x x)
        z (inc y)]
    (str z)))

(s/defn with-schemas :- s/Str
  [x :- s/Int]
  (with-let-not-schema x))

(s/defn with-input-schema
  [x :- s/Int]
  (with-let-not-schema x))

(s/defn with-output-schema :- s/Str
  [x]
  (with-let-not-schema x))

(s/defn with-multiple-args-and-function-call :- s/Str
  "Another function."
  [x :- s/Int
   y :- s/Bool]
  (if y
    (with-schemas x)
    (-> x - with-schemas)))

(s/defn with-one-input-schema
  [x :- s/Int
   y]
  (if y
    (with-schemas x)
    (-> x - with-schemas)))

(s/defn mismatched-maybe-schema :- s/Str
  "Another function. This one may not work."
  [x :- (s/maybe s/Int)]
  (with-schemas x))

(s/defn mismatched-wrong-schema :- s/Str
  "This one definitely won't work."
  [x :- s/Str]
  (with-schemas x))

(defn with-schematized-anonymous-function
  [z]
  (let [l* (s/fn :- s/Str [x :- s/Int] (with-let-not-schema x))]
    (l* z)))

(s/defn with-let-variables
  [z :- s/Int]
  (let [n 3
        m (+ n z)]
    (mismatched-maybe-schema m)))

(defn multiple-arities-no-schemas
  ([]
   (multiple-arities-no-schemas 1))
  ([x]
   (multiple-arities-no-schemas x 2))
  ([x & y]
   (apply + x y)))

(s/defn multiple-arities-with-schemas :- s/Int
  ([]
   (multiple-arities-with-schemas 1))
  ([x :- s/Int]
   (multiple-arities-with-schemas x 2))
  ([x y]
   (multiple-arities-with-schemas x y 3))
  ([x :- s/Int
    y :- s/Int
    & z :- [s/Int]]
   (apply + x y z)))

(s/defn multiple-arities :- s/Str
  ([x :- s/Int]
   (multiple-arities [nil x]))
  ([y :- (s/maybe s/Int)
    z :- s/Int]
   ((fnil + 0) y z)))

(s/defn optional-args :- s/Str
  [x :- s/Int
   & others]
  (apply str x others))

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

(s/defn sample-schema-bad-fn :- s/Int
  [x :- s/Int]
  (int-add 1 (int-add nil x)))

(defn sample-bad-fn
  [x]
  (int-add 1 (int-add nil x)))

(defn sample-bad-let-fn
  [x]
  (let [y nil]
    (int-add x y)))

(defn sample-let-bad-fn
  [x]
  (let [y (int-add 1 nil)
        z (int-add 2 3)]
    (int-add x y z)))

(defn sample-multi-line-let-body
  [x]
  (let [y (- 2 3)
        z (* 7 8)
        f (fn [x] nil)
        w nil
        u (int-add 2 3 4 nil)]
    (int-add 2 nil)
    (int-add w 1 x y z)
    (int-add 3 u)
    (int-add 1 (f x)))
  (int-add nil x)
  (int-add 2 3))

(defn sample-let-mismatched-types
  [x]
  (let [s "hi"]
    (int-add x s)))

(s/defn maybe-multi-step-takes-str :- (s/maybe s/Str)
  [x :- (s/maybe s/Str)]
  x)

(s/defn maybe-multi-step-takes-int :- s/Int
  [x :- s/Int]
  x)

(s/defn maybe-multi-step-takes-maybe-int :- (s/maybe s/Int)
  [x :- (s/maybe s/Int)]
  x)

(s/defn flat-maybe-multi-step-f :- (s/maybe s/Int)
  [flag]
  (when flag
    1))

(s/defn flat-maybe-multi-step-g :- (s/maybe s/Int)
  [flag]
  (flat-maybe-multi-step-f flag))

(defn flat-maybe-base-type-failure
  [flag]
  (maybe-multi-step-takes-str (flat-maybe-multi-step-g flag)))

(defn flat-maybe-nil-failure
  [flag]
  (maybe-multi-step-takes-int (flat-maybe-multi-step-g flag)))

(defn flat-maybe-success
  [flag]
  (maybe-multi-step-takes-maybe-int (flat-maybe-multi-step-g flag)))

(s/defschema MaybeValueDesc
  {:value (s/maybe s/Int)})

(s/defn nested-maybe-multi-step-f :- MaybeValueDesc
  [flag]
  {:value (when flag
            1)})

(s/defn nested-maybe-multi-step-g :- MaybeValueDesc
  [flag]
  (nested-maybe-multi-step-f flag))

(defn nested-maybe-base-type-failure
  [flag]
  (maybe-multi-step-takes-str (get (nested-maybe-multi-step-g flag) :value)))

(defn nested-maybe-nil-failure
  [flag]
  (maybe-multi-step-takes-int (get (nested-maybe-multi-step-g flag) :value)))

(defn nested-maybe-success
  [flag]
  (maybe-multi-step-takes-maybe-int (get (nested-maybe-multi-step-g flag) :value)))
