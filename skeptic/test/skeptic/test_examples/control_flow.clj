(ns skeptic.test-examples.control-flow
  (:require [schema.core :as s]
            [skeptic.test-examples.basics :refer [int-add]]))

(defn sample-let-fn
  [x]
  (let [y 2]
    (int-add x y)))

(defn sample-nil-local-arg-fn
  [x]
  (let [y nil]
    (int-add x y)))

(defn sample-bad-local-provenance-fn
  [x]
  (let [y (int-add 1 nil)
        z (int-add 2 3)]
    (int-add x y z)))

(defn sample-shadow-bad-fn
  [x]
  (let [x (int-add 9 9)]
    x)
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

(defn sample-let-mismatched-types
  [x]
  (let [s "hi"]
    (int-add x s)))

(s/defn deep-unary :- s/Int
  []
  (inc
   (inc
    (inc
     (inc
      (inc
       (inc
        (inc
         (inc
          (inc
           (inc
            (inc
             (inc
              (inc
               (inc
                (inc
                 (inc
                  (inc
                   (inc
                    (inc
                     (inc 0)))))))))))))))))))))

(s/defn loop-sum-success :- s/Int
  []
  (loop [acc 0
         n 3]
    (if (clojure.core/zero? n)
      acc
      (recur (int-add acc n) (clojure.core/dec n)))))

(s/defn loop-returns-int-vec-literal :- [s/Int]
  []
  (loop [] [1 2 3]))

(s/defn loop-returns-nested-schema-map :- {:a s/Str :b [s/Int]}
  []
  (loop [] {:a "hi" :b [1 2]}))

(s/defn loop-recur-accumulates-int-vec :- [s/Int]
  []
  (loop [acc [1] more [2 3]]
    (if (seq more)
      (recur (conj acc (first more)) (rest more))
      acc)))

(s/defn loop-recur-nested-schema-map :- {:a s/Str :b [s/Int]}
  []
  (loop [m {:a "a" :b [1]} n 1]
    (if (clojure.core/zero? n)
      m
      (recur {:a (clojure.core/str (:a m) "b")
              :b (conj (:b m) 2)}
             (clojure.core/dec n)))))

(s/defn for-first-int-success :- s/Int
  []
  (int-add (first (for [x [1 2 3]] (int-add x 0))) 0))

(s/defn for-declared-int-seq-output :- [s/Int]
  []
  (for [x [1 2 3]] (inc x)))

(s/defn for-declared-str-seq-body-int-seq :- [s/Str]
  []
  (for [x [1 2 3]] (inc x)))

(s/defn for-even-str-odd-int-declared-int-seq :- [s/Int]
  []
  (for [x [1 2 3]] (if (even? x) (str x) x)))

(s/defn for-even-str-odd-int-declared-str-seq :- [s/Str]
  []
  (for [x [1 2 3]] (if (even? x) (str x) x)))

(s/defn for-even-str-odd-int-declared-cond-pre-seq :- [(s/cond-pre s/Int s/Str)]
  []
  (for [x [1 2 3]] (if (even? x) (str x) x)))

(s/defn loop-recur-type-mismatch :- s/Int
  []
  (loop [x 0]
    (if (clojure.core/< x 1)
      (recur "not-int")
      x)))


(s/defn cond-three-branch-join :- (s/cond-pre s/Int s/Str s/Keyword)
  [x :- s/Int]
  (cond
    (pos? x) 1
    (neg? x) "negative"
    :else :zero))

(s/defn cond-boolean-exhaustive-output-success :- (s/enum "a" "b")
  [a :- s/Int]
  (cond
    (pos? a) "a"
    (not (pos? a)) "b"))

(s/def sum-type-exhaustive-map :- {:a s/Int}
  {:a 1})

(s/defn cond-get-union-predicate-exhaustive-output-success :- (s/enum "a" "b")
  []
  (let [n (get sum-type-exhaustive-map :a "fallback")]
    (cond
      (string? n) "a"
      (int? n) "b")))

(s/defn cond-enum-equality-exhaustive-output-success :- (s/enum "a" "b")
  [x :- (s/enum :a :b)]
  (cond
    (= x :a) "a"
    (= x :b) "b"))

(s/defn case-enum-exhaustive-output-success :- (s/enum "a" "b")
  [x :- (s/enum :a :b)]
  (case x
    :a "a"
    :b "b"))

(s/defn condp-enum-equality-exhaustive-output-success :- (s/enum "a" "b")
  [x :- (s/enum :a :b)]
  (condp = x
    :a "a"
    :b "b"))
