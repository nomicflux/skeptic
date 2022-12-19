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

(s/defn sample-half-schema-fn
  [x :- s/Int]
  (int-add 1 (int-add 2 x)))

(s/defn sample-schema-fn :- s/Int
  [x :- s/Int]
  (int-add 1 (int-add 2 x)))

(defn sample-fn
  [x]
  (int-add 1 (int-add 2 x)))

(s/defn sample-schema-bad-fn :- s/Int
  [x :- s/Int]
  (int-add 1 (int-add nil x)))

(defn sample-bad-fn
  [x]
  (int-add 1 (int-add nil x)))

(defn sample-let-fn
  [x]
  (let [y 2]
    (int-add x y)))

(defn sample-bad-let-fn
  [x]
  (let [y nil]
    (int-add x y)))

(defn sample-let-bad-fn
  [x]
  (let [y (int-add 1 nil)
        z (int-add 2 3)]
    (int-add x y z)))

(defn sample-if-fn
  [x]
  (if x
    1
    2))

(defn sample-if-mixed-fn
  [x]
  (if x
    1
    "hi"))

(defn sample-do-fn
  [x]
  (do (int-add 1 2)
      nil
      "hi"))

(defn sample-try-catch-fn
  [x]
  (try :a :b 1
       (catch Exception e :c "hi")))

(defn sample-try-finally-fn
  [x]
  (try :a :b 1
       (finally nil "hi")))

(defn sample-try-catch-finally-fn
  [x]
  (try :a 1
       (catch Exception e :b nil)
       (finally :c "hi")))

(defn sample-throw-fn
  [x]
  (throw (AssertionError. "oops")))

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

(let [x 2]
  (defn sample-let-over-defn
   [y]
    (int-add x y)))

(let [x nil]
  (defn sample-bad-let-over-defn
   [y]
    (int-add x y)))

(defn sample-multi-line-body
  [x]
  (int-add 1 x)
  (int-add nil x)
  (int-add 2 3))

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

(defn sample-mismatched-types
  [x]
  (int-add x "hi"))

(defn sample-let-mismatched-types
  [x]
  (let [s "hi"]
    (int-add x s)))

(defn sample-str-fn
  [x]
  (str x)
  (int-add 1 (str x))
  (let [y (str nil)]
    (int-add 1 y)))

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

;; TODO: this example needs parametricity to work
;; (it needs to know that (fn [f] (f x)) takes some specific but not pre-determined schema,
;; then applies it)
(defn sample-bad-parametric-fn
  [x]
  (let [f (fn [_y] nil)
        g (fn [f] (f x))]
    (+ 1 (g f))))
