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

(s/defn sample-half-schema-fn
  [x :- s/Int]
  (+ 1 (+ 2 x)))

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

(defn sample-let-fn
  [x]
  (let [y 2]
    (+ x y)))

(defn sample-bad-let-fn
  [x]
  (let [y nil]
    (+ x y)))

(defn sample-let-bad-fn
  [x]
  (let [y (+ 1 nil)
        z (+ 2 3)]
    (+ x y z)))

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
  (do (+ 1 2)
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
  ((if x + -) 1 2))

(defn sample-var-fn-fn
  [x]
  ((if x #'+ #'-) 1 2))

(defn sample-found-var-fn-fn
  [x]
  ((if x #'+ -) 1 2))

(defn sample-missing-var-fn-fn
  [x]
  ((if x + #'-) 1 2))

(let [x 2]
  (defn sample-let-over-defn
   [y]
    (+ x y)))

(let [x nil]
  (defn sample-bad-let-over-defn
   [y]
    (+ x y)))

(defn sample-internal-defn
  [x]
  (defn f [y] (+ x y))
  (f 2))

(defn sample-internal-def
  [x]
  (def y 1)
  (+ x y))

(defn sample-multi-line-body
  [x]
  (+ 1 x)
  (+ nil x)
  (+ 2 3))

(defn sample-multi-line-let-body
  [x]
  (let [y (- 2 3)
        z (* 7 8)
        f (fn [x] nil)
        w nil
        u (+ 2 3 4 nil)]
    (+ 2 nil)
    (+ w 1 x y z)
    (+ 3 u)
    (+ 1 (f x)))
  (+ nil x)
  (+ 2 3))

(defn sample-mismatched-types
  [x]
  (+ x "hi"))

(defn sample-let-mismatched-types
  [x]
  (let [s "hi"]
    (+ x s)))

(defn sample-str-fn
  [x]
  (str x)
  (+ 1 (str x))
  (let [y (str nil)]
    (+ 1 y)))

(defn sample-let-fn-fn
  [x]
  (let [f (fn [y] (+ y 1))]
    (f x)))

(defn sample-let-fn-bad1-fn
  [x]
  (let [f (fn [y] (+ y nil))]
    (f x)))

(defn sample-let-fn-bad2-fn
  [x]
  (let [f (fn [y] (+ y x))]
    (f nil)))

(defn sample-functional-fn
  [x]
  (let [f (fn [y] (+ y 1))
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

;;;; sample-bad-fn expanded & resolved
;; (let*
;;  [ufv__
;;   schema.utils/use-fn-validation
;;   output-schema137781
;;   schema.core/Int
;;   input-schema137782
;;   [(schema.core/one schema.core/Int 'x)]
;;   input-checker137783
;;   (new clojure.lang.Delay (fn* [] (schema.core/checker input-schema137782)))
;;   output-checker137784
;;   (new clojure.lang.Delay (fn* [] (schema.core/checker output-schema137781)))]
;;  (let*
;;   [ret__3215__auto__
;;    (def
;;     sample-schema-bad-fn
;;     (fn*
;;      ([G__137785]
;;       (let*
;;        [validate__1628__auto__ (. ufv__ clojure.core/get)]
;;        (if
;;         validate__1628__auto__
;;         (do
;;          (let*
;;           [args__1629__auto__ [G__137785]]
;;           (if
;;            schema.core/fn-validator
;;            (schema.core/fn-validator
;;             :input
;;             'sample-schema-bad-fn
;;             input-schema137782
;;             @input-checker137783
;;             args__1629__auto__)
;;            (let*
;;             [temp__5735__auto__ (@input-checker137783 args__1629__auto__)]
;;             (if
;;              temp__5735__auto__
;;              (do
;;               (let*
;;                [error__1630__auto__ temp__5735__auto__]
;;                (throw
;;                 (new
;;                  clojure.lang.ExceptionInfo
;;                  (schema.utils/format*
;;                   "Input to %s does not match schema: \n\n\t [0;33m  %s [0m \n\n"
;;                   'sample-schema-bad-fn
;;                   (clojure.core/pr-str error__1630__auto__))
;;                  {:type :schema.core/error,
;;                   :schema input-schema137782,
;;                   :value args__1629__auto__,
;;                   :error error__1630__auto__}))))))))))
;;        (let*
;;         [o__1631__auto__
;;          (loop* [x G__137785] (clojure.core/+ 1 (clojure.core/+ nil x)))]
;;         (if
;;          validate__1628__auto__
;;          (do
;;           (if
;;            schema.core/fn-validator
;;            (schema.core/fn-validator
;;             :output
;;             'sample-schema-bad-fn
;;             output-schema137781
;;             @output-checker137784
;;             o__1631__auto__)
;;            (let*
;;             [temp__5735__auto__ (@output-checker137784 o__1631__auto__)]
;;             (if
;;              temp__5735__auto__
;;              (do
;;               (let*
;;                [error__1630__auto__ temp__5735__auto__]
;;                (throw
;;                 (new
;;                  clojure.lang.ExceptionInfo
;;                  (schema.utils/format*
;;                   "Output of %s does not match schema: \n\n\t [0;33m  %s [0m \n\n"
;;                   'sample-schema-bad-fn
;;                   (clojure.core/pr-str error__1630__auto__))
;;                  {:type :schema.core/error,
;;                   :schema output-schema137781,
;;                   :value o__1631__auto__,
;;                   :error error__1630__auto__})))))))))
;;         o__1631__auto__)))))]
;;   (schema.utils/declare-class-schema!
;;    (schema.utils/fn-schema-bearer sample-schema-bad-fn)
;;    (schema.core/->FnSchema output-schema137781 [input-schema137782]))
;;   ret__3215__auto__))
