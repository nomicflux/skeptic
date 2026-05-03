(ns skeptic.test-examples.nullability
  (:require [clojure.string :as str]
            [schema.core :as s]
            [skeptic.test-examples.basics :as basics]
            [skeptic.test-examples.nullability-xns-schema :as fn-ns]))

(s/defn test-eq-nil :- (s/eq nil)
  []
  (doseq [x [1 2 3]]
    (println x)))

(s/defn take-val :- s/Int
  [x :- s/Int]
  x)

(s/defn process-val :- (s/maybe s/Int)
  [x :- (s/maybe s/Int)]
  (some-> x take-val))

(s/defn multi-step-some->-success :- (s/maybe s/Int)
  []
  (some-> {:a {:b {:c nil}}} :a :b :c take-val))

(s/defn when-truthy-nil-local-success :- (s/maybe s/Int)
  []
  (let [x nil]
    (when x
      (take-val x))))

(s/defn maybe-x :- (s/maybe s/Int)
  []
  nil)

(s/defn when-and-some?-nil-success :- (s/maybe s/Int)
  []
  (let [x (maybe-x)]
    (when (some? x)
      (take-val x))))

(s/defn when-and-some?-and-nil-success :- (s/maybe s/Int)
  []
  (let [x (maybe-x)]
    (when (and (some? x)
               (pos? x))
      (take-val x))))

(s/defn when-and-some?-multi-nil-success :- (s/maybe s/Int)
  []
  (let [x (maybe-x)
        y (maybe-x)]
    (when (and (some? x)
               (and (pos? x)
                    (some? y)))
      (take-val x))))

(s/defn when-not-throw-nil-local-success :- s/Int
  [x :- (s/maybe s/Int)]
  (when-not x
    (throw (ex-info "no x" {})))
  (take-val x))

(s/defn maybe-int-into-int-arg-failure :- s/Int
  []
  (take-val (maybe-x)))

(s/defn take-symbol :- s/Symbol
  [x :- s/Symbol]
  x)

(s/defn maybe-symbol :- (s/maybe s/Symbol)
  []
  nil)

(s/defn maybe-symbol-into-symbol-arg-failure :- s/Symbol
  []
  (take-symbol (maybe-symbol)))

(s/defn lookup-int :- (s/maybe s/Int)
  [m :- {s/Keyword s/Int} k :- s/Keyword]
  (get m k))

(s/defn add-ints :- s/Int
  [x :- s/Int y :- s/Int]
  (+ x y))

(s/defn chained-maybe-into-int-arg-failure :- s/Int
  []
  (add-ints (lookup-int {} :a) (lookup-int {} :b)))

(s/defschema S
  {:k1 s/Str :k2 s/Str})

(s/defn produce :- (s/maybe S)
  []
  nil)

(s/defn consume :- s/Any
  [k1 :- s/Str
   k2 :- s/Str]
  [k1 k2])

(s/defn guarded-keys-caller :- s/Any
  [gate :- s/Any]
  (let [pair (when gate (produce))
        {:keys [k1 k2]} pair]
    (when pair
      (consume k1 k2))))

(s/defn takes-non-nil-str :- s/Str
  [s :- s/Str]
  s)

(s/defn when-not-blank-maybe-str-success :- (s/maybe s/Str)
  [x :- (s/maybe s/Str)]
  (when-not (str/blank? x)
    (takes-non-nil-str x)))

(s/defn when-and-seq-truthy-any-success :- s/Any
  [{:keys [t xs]} :- {s/Keyword s/Any}]
  (let [x (when (seq xs) (first xs))]
    (when (and (= t "a") x)
      (takes-non-nil-str x))))

(s/defn when-and-count-truthy-any-success :- s/Any
  [{:keys [t xs]} :- {s/Keyword s/Any}]
  (let [x (when (= (count xs) 1) (first xs))]
    (when (and (= t "a") x)
      (takes-non-nil-str x))))

(s/defn or-fallback-destructured-success :- {:a s/Str}
  [{:keys [x]} :- {:x (s/maybe s/Str)}]
  {:a (or x "fallback")})

(s/defn or-fallback-nil-middle-success :- {:a s/Str}
  [{:keys [x]} :- {:x (s/maybe s/Str)}]
  {:a (or x nil "fallback")})

(s/defn or-fallback-nil-last-success :- {:a s/Str}
  [{:keys [x]} :- {:x (s/maybe s/Str)}]
  {:a (or x "fallback" nil)})

(s/defschema Strs {:a (s/maybe s/Str) :b s/Str})

(s/defn if-or-nil-blank-destructured-narrows-success :- {:a s/Str}
  [{:keys [a b]} :- Strs]
  {:a (if (or (nil? a) (str/blank? a)) (str "a-" b) a)})

(s/defschema OptStrs {(s/optional-key :a) s/Str :b s/Str})

(s/defn if-or-nil-blank-optional-key-narrows-success :- {:a s/Str}
  [{:keys [a b]} :- OptStrs]
  {:a (if (or (nil? a) (str/blank? a)) (str "a-" b) a)})

(s/defn if-or-nil-blank-direct-param-narrows-success :- {:k s/Str}
  [a :- (s/maybe s/Str)]
  {:k (if (or (nil? a) (str/blank? a)) "u" a)})

(s/defn or-only-nil-alternative-failure :- {:a s/Str}
  [{:keys [x]} :- {:x (s/maybe s/Str)}]
  {:a (or x nil)})

(s/defn or-nil-then-nullable-failure :- {:a s/Str}
  [{:keys [x]} :- {:x (s/maybe s/Str)}]
  {:a (or x nil x)})

(s/defn presents-str :- s/Str
   [s :- (s/maybe s/Str)]
   (when (str/blank? s)
     (throw (ex-info "Blank/nil" {})))
   s)

(s/defn takes-maybe-constrained :- (s/maybe basics/PosInt)
  [x :- (s/maybe basics/PosInt)]
  x)

(defn nil-satisfies-maybe-constrained-success
  []
  (takes-maybe-constrained nil))

(s/defn non-null-transform :- s/Num
  [x :- s/Num]
  (* x 2))

(s/defn some-to-lambda-success
  [input :- (s/maybe s/Num)]
  (some-> input
          non-null-transform
          (#(- %))))

(s/defschema Hooks
  {(s/optional-key :on-complete) (s/=> (s/eq nil) s/Int)
   (s/optional-key :on-step) (s/=> (s/eq nil) s/Int)
   (s/optional-key :on-error) (s/=> (s/eq nil) s/Str)})

(s/defn opaque-logging-fn :- (s/maybe s/Any)
  [msg]
  ;; Do not create a type for pprint to pass
  (clojure.pprint/pprint msg))

(s/defn takes-str :- s/Str
  [x :- s/Str]
  x)

(s/defn let-bound-when-truthy-narrows-success
  [k1 k2]
  (let [x (when k1 k2)]
    (when (and (some? k1) x)
      (takes-str x))))

(s/defn eq-nil-return-success :- Hooks
  []
  {:on-complete (fn [_] (opaque-logging-fn "complete"))
   :on-step (fn [x] (doseq [y [1 2 3]] (when (= x y) (println "equal"))))
   :on-error (fn [_] (println "oops"))})

(s/defn pre-some?-narrows-map-key-success
  [m :- {:x (s/maybe s/Int)}]
  {:pre [(some? (:x m))]}
  (take-val (:x m)))

(s/defn if-string?-narrows-map-key-success
  [m :- {:s (s/maybe s/Str)}]
  (if (string? (:s m))
    (str/upper-case (:s m))
    ""))

(s/defn when-some?-on-key-success
  [m :- {:x (s/maybe s/Int)}]
  (when (some? (:x m))
    (take-val (:x m))))

(s/defn pre-pos?-narrows-map-key-success
  [m :- {:n s/Int}]
  {:pre [(pos? (:n m))]}
  (take-val (:n m)))

(s/defn when-and-pred-nil-throw-correlated-success
  [pred?
   m :- (s/maybe s/Str)]
  (when (and pred? (nil? m))
    (throw (Exception. "err")))
  (if pred?
    (takes-non-nil-str m)
    nil))

(s/defn when-and-pred-not-contains-throw-correlated-success
  [pred?
   m :- {(s/optional-key :k) s/Int}]
  (when (and pred? (not (contains? m :k)))
    (throw (Exception. "err")))
  (if pred?
    (take-val (:k m))
    nil))

(s/defn return-maybe :- (s/maybe s/Int)
  []
  nil)

(s/defn let-bound-maybe-int-into-plus-failure
  []
  (let [x (return-maybe)
        y (+ 1 1)
        z (+ 1 y)
        w (+ 1 z)
        u (+ y x)]
    (+ x w)))

(s/defn xns-let-bound-maybe-int-into-int-arg-failure
  []
  (let [x (fn-ns/f)
        y (take-val x)]
    y))
