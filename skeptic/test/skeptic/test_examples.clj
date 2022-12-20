(ns skeptic.test-examples
  (:require [schema.core :as s]))

;; Note: updating this file while in the REPL may cause functions to break
;; if they rely on loading the source code & resolving references. This is why
;; it is better to put stable test cases here, then do active work on the tests
;; in question in a separate file.

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

(defn sample-namespaced-keyword-fn
  [x]
  (let [y {::key1 1
           ::s/key2 2}]
    (int-add x (::s/key2 y))
    (int-add x (::key1 y))))

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

;; TODO: Currently broken, fix
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

(def sample-dict
  {"skeptic.test-examples/int-add"
   {:name "skeptic.test-examples/int-add"
    :schema (s/=> s/Int s/Int)
    :output s/Int
    :arglists {1 {:arglist ['x], :schema [{:schema s/Int, :optional? false, :name 'x}]},
               2
               {:arglist ['y 'z],
                :schema
                [{:schema s/Int, :optional? false, :name 'y}
                 {:schema s/Int, :optional? false, :name 'z}]}
               :varargs
               {:arglist ['y 'z ['more]],
                :count 3
                :schema
                [{:schema s/Int, :optional? false, :name 'y}
                 {:schema s/Int, :optional? false, :name 'z}
                 s/Int]}}}
  "clojure.core/str"
   {:name "clojure.core/str"
    :schema (s/=> s/Str s/Any)
    :output s/Str
    :arglists {1 {:arglist ['s], :schema [{:schema s/Any, :optional? false, :name 's}]},
               :varargs
               {:arglist ['s ['more]],
                :count 2
                :schema
                [{:schema s/Any, :optional? false, :name 's}
                 s/Any]}}}})
