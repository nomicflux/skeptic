(ns skeptic.examples
  (:require [schema.core :as s]
            [clojure.walk :as walk]
            [skeptic.core :refer :all]
            [clojure.repl :as repl]
            [clojure.string :as str]))

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

(s/defn sample-schema-fn :- s/Int
  [x :- s/Int]
  (+ 1 (+ 2 x)))

(defn sample-fn
  [x]
  (+ 1 (+ 2 x)))

(s/defn sample-schema-bad-fn :- s/Int
  [x :- s/Int]
  (+ 1 (+ nil x)))

(defn sample-bad-fn
  [x]
  (+ 1 (+ nil x)))

(defn sample-bad-let-fn
  [x]
  (let [y nil]
    (+ x y)))

(defn sample-let-bad-fn
  [x]
  (let [y (+ 1 nil)
        z (+ 2 3)]
    (+ x y z)))
