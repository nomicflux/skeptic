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
        w nil
        u (+ 2 3 4 nil)]
    (+ 2 nil)
    (+ w 1 x y z)
    (+ 3 u))
  (+ nil x)
  (+ 2 3))

;; (let*
;;  [ufv__
;;   #'schema.utils/use-fn-validation
;;   output-schema38457
;;   #'schema.core/Int
;;   input-schema38458
;;   [(#'schema.core/one #'schema.core/Int 'x)]
;;   input-checker38459
;;   (#'clojure.core/delay (#'schema.core/checker input-schema38458))
;;   output-checker38460
;;   (#'clojure.core/delay (#'schema.core/checker output-schema38457))]
;;  (#'clojure.core/let
;;   [ret__3203__auto__
;;    (#'clojure.core/defn
;;     sample-schema-bad-fn
;;     {:schema (#'schema.core/->FnSchema output-schema38457 [input-schema38458]),
;;      :doc "Inputs: [x :- s/Int]\n  Returns: s/Int",
;;      :raw-arglists '([x :- #'schema.core/Int]),
;;  '([x])}
;;     ([G__38461]
;;      (#'clojure.core/let
;;       [validate__1616__auto__
;;        (#'schema.macros/if-cljs (#'clojure.core/deref ufv__) (.get ufv__))]
;;       (#'clojure.core/when
;;        validate__1616__auto__
;;        (#'clojure.core/let
;;         [args__1617__auto__ [G__38461]]
;;         (if
;;          #'schema.core/fn-validator
;;          (#'schema.core/fn-validator
;;           :input
;;           'sample-schema-bad-fn
;;           input-schema38458
;;           (#'clojure.core/deref input-checker38459)
;;           args__1617__auto__)
;;          (#'clojure.core/when-let
;;           [error__1618__auto__
;;            ((#'clojure.core/deref input-checker38459) args__1617__auto__)]
;;           (#'schema.macros/error!
;;            (#'schema.utils/format*
;;             "Input to %s does not match schema: \n\n\t [0;33m  %s [0m \n\n"
;;             'sample-schema-bad-fn
;;             (#'clojure.core/pr-str error__1618__auto__))
;;            {:schema input-schema38458,
;;             :value args__1617__auto__,
;;             :error error__1618__auto__})))))
;;       (#'clojure.core/let
;;        [o__1619__auto__
;;         (#'clojure.core/loop
;;          [x G__38461]
;;          (#'clojure.core/+ 1 (#'clojure.core/+ nil x)))]
;;        (#'clojure.core/when
;;         validate__1616__auto__
;;         (if
;;          #'schema.core/fn-validator
;;          (#'schema.core/fn-validator
;;           :output
;;           'sample-schema-bad-fn
;;           output-schema38457
;;           (#'clojure.core/deref output-checker38460)
;;           o__1619__auto__)
;;          (#'clojure.core/when-let
;;           [error__1618__auto__
;;            ((#'clojure.core/deref output-checker38460) o__1619__auto__)]
;;           (#'schema.macros/error!
;;            (#'schema.utils/format*
;;             "Output of %s does not match schema: \n\n\t [0;33m  %s [0m \n\n"
;;             'sample-schema-bad-fn
;;             (#'clojure.core/pr-str error__1618__auto__))
;;            {:schema output-schema38457,
;;             :value o__1619__auto__,
;;             :error error__1618__auto__}))))
;;        o__1619__auto__))))]
;;   (#'schema.utils/declare-class-schema!
;;    (#'schema.utils/fn-schema-bearer sample-schema-bad-fn)
;;    (#'schema.core/->FnSchema output-schema38457 [input-schema38458]))
;;   ret__3203__auto__))
