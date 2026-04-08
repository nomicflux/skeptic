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

;(with-analysis 'int-add)

(s/defn sample-half-annotated-fn
  [x :- s/Int]
  (int-add 1 (int-add 2 x)))

(s/defn sample-annotated-fn :- s/Int
  [x :- s/Int]
  (int-add 1 (int-add 2 x)))

(defn sample-fn
  [x]
  (int-add 1 (int-add 2 x)))

;(def sample-analysis (with-analysis 'sample-fn))

(defn sample-namespaced-keyword-fn
  [x]
  (let [y {::key1 1
           ::s/key2 2}]
    (int-add x (::s/key2 y))
    (int-add x (::key1 y))))

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

(s/defn sample-constrained-output-fn :- (s/constrained s/Int pos?)
  [x :- s/Int]
  x)

(s/defn sample-bad-constrained-output-fn :- (s/constrained s/Int pos?)
  [x :- s/Str]
  x)

(s/defschema NestedNameDesc
  {:user {:name s/Str}})

(s/defschema IntPair
  [s/Int s/Int])

(s/defschema IntTriple
  [s/Int s/Int s/Int])

(s/defschema IntQuad
  [s/Int s/Int s/Int s/Int])

(s/defn takes-nested-name :- s/Keyword
  [x :- NestedNameDesc]
  :ok)

(defn nested-map-input-failure
  []
  (takes-nested-name {:user {:name :bad}}))

(defn nested-map-input-success
  []
  (takes-nested-name {:user {:name "ok"}}))

(s/defn takes-int-pair :- s/Keyword
  [xs :- IntPair]
  :ok)

(s/defn takes-int-vec :- s/Keyword
  [xs :- [s/Int]]
  :ok)

(s/defn takes-int-triple :- s/Keyword
  [xs :- IntTriple]
  :ok)

(s/defn takes-int-quad :- s/Keyword
  [xs :- IntQuad]
  :ok)

(s/defn bad-int-pair-helper :- [s/Int s/Str]
  []
  [1 "oops"])

(defn vector-input-failure
  []
  (takes-int-pair (bad-int-pair-helper)))

(defn vector-input-success
  []
  (takes-int-pair [1 2]))

(defn vector-triple-to-homogeneous-success
  [x y z]
  (takes-int-vec [x y z]))

(defn vector-triple-to-fixed-success
  [x y z]
  (takes-int-triple [x y z]))

(defn vector-triple-to-pair-failure
  [x y z]
  (takes-int-pair [x y z]))

(defn vector-triple-to-quad-failure
  [x y z]
  (takes-int-quad [x y z]))

(defn sample-bad-fn
  [x]
  (int-add 1 (int-add nil x)))

(defn sample-let-fn
  [x]
  (let [y 2]
    (int-add x y)))

;(def sample-let-analysis (with-analysis 'sample-let-fn))

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

(s/defn map-literal-input-success :- s/Int
  []
  (int-add (:a {:a 1}) 0))

(s/defn map-var-input-success :- s/Int
  [m :- {:a s/Int}]
  (int-add (:a m) 0))

(s/defn map-annotated-fn-input-success :- s/Int
  [m :- {:a s/Int}]
  (int-add (:a m) 0))

(defn map-unannotated-fn-input-success
  [_m]
  (int-add 1 1))

(s/defn simple-map-output-success :- {:a s/Int}
  []
  {:a 1})

(s/defn vec-literal-input-success :- s/Int
  []
  (int-add (first [1 2]) 0))

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

(s/defn narrowing-string-predicate-success :- s/Int
  [x :- (s/cond-pre s/Str s/Int)]
  (if (string? x)
    (count x)
    (int-add x 1)))

(s/defn narrowing-keyword-invoke-presence-success :- s/Int
  [m :- {:a s/Int}]
  (if (:a m)
    (int-add (:a m) 1)
    0))

(s/defn narrowing-case-success :- s/Int
  [x :- (s/cond-pre (s/eq :a) (s/eq :b))]
  (case x
    :a 1
    :b 2))

(s/defn narrowing-assoc-get-success :- s/Int
  [m :- {:a s/Int}]
  (int-add (:a (assoc m :a 1)) 0))

(s/defn narrowing-fn-helper :- s/Int
  [x :- s/Int]
  x)

(s/defn narrowing-fn-qmark-success :- s/Int
  [f :- s/Any]
  (if (fn? f)
    (narrowing-fn-helper 1)
    0))

(s/defn fn-type-satisfies-pred-fn-success :- (s/pred fn?)
  [f :- (s/=> s/Int s/Int)]
  f)

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

(defn ^:always-validate sample-metadata-fn
  {:something-else true}
  [x]
  (int-add x nil))

(defn sample-doc-fn
  "Doc here."
  [x]
  (int-add x nil))

(defn ^:always-validate sample-doc-and-metadata-fn
  "Doc here."
  {:something-else true}
  [x]
  (int-add x nil))

(defn sample-fn-once
  [x]
  ((^{:once true} fn* [y] (int-add y nil))
   x))

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

(def sample-dict
  {'skeptic.test-examples/int-add
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
   'clojure.core/str
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

(s/defschema ConditionalIntOrStr
  (s/conditional integer? s/Int string? s/Str))

(s/defschema CondPreIntOrStr
  (s/cond-pre s/Int s/Str))

(s/defschema EitherIntOrStr
  (s/either s/Int s/Str))

(s/defschema IfIntOrStr
  (s/if integer? s/Int s/Str))

(s/defschema BothAnyInt
  (s/both s/Any s/Int))

(s/defschema BothIntStr
  (s/both s/Int s/Str))

(s/defn takes-conditional-branch :- s/Keyword
  [x :- ConditionalIntOrStr]
  :ok)

(s/defn takes-cond-pre-branch :- s/Keyword
  [x :- CondPreIntOrStr]
  :ok)

(s/defn takes-either-branch :- s/Keyword
  [x :- EitherIntOrStr]
  :ok)

(s/defn takes-if-branch :- s/Keyword
  [x :- IfIntOrStr]
  :ok)

(s/defn takes-both-any-int :- s/Keyword
  [x :- BothAnyInt]
  :ok)

(s/defn takes-both-int-str :- s/Keyword
  [x :- BothIntStr]
  :ok)

(defn conditional-input-int-success
  []
  (takes-conditional-branch 1))

(defn conditional-input-str-success
  []
  (takes-conditional-branch "hi"))

(defn conditional-input-keyword-failure
  []
  (takes-conditional-branch :bad))

(defn cond-pre-input-int-success
  []
  (takes-cond-pre-branch 1))

(defn cond-pre-input-str-success
  []
  (takes-cond-pre-branch "hi"))

(defn cond-pre-input-keyword-failure
  []
  (takes-cond-pre-branch :bad))

(defn either-input-int-success
  []
  (takes-either-branch 1))

(defn either-input-str-success
  []
  (takes-either-branch "hi"))

(defn either-input-keyword-failure
  []
  (takes-either-branch :bad))

(defn if-input-int-success
  []
  (takes-if-branch 1))

(defn if-input-str-success
  []
  (takes-if-branch "hi"))

(defn if-input-keyword-failure
  []
  (takes-if-branch :bad))

(defn both-any-int-input-success
  []
  (takes-both-any-int 1))

(defn both-any-int-input-str-failure
  []
  (takes-both-any-int "hi"))

(defn both-int-str-input-int-failure
  []
  (takes-both-int-str 1))

(defn both-int-str-input-str-failure
  []
  (takes-both-int-str "hi"))

(s/defn conditional-output-int-success :- ConditionalIntOrStr
  []
  1)

(s/defn conditional-output-str-success :- ConditionalIntOrStr
  []
  "hi")

(s/defn conditional-output-keyword-failure :- ConditionalIntOrStr
  []
  :bad)

(s/defn cond-pre-output-int-success :- CondPreIntOrStr
  []
  1)

(s/defn cond-pre-output-str-success :- CondPreIntOrStr
  []
  "hi")

(s/defn cond-pre-output-keyword-failure :- CondPreIntOrStr
  []
  :bad)

(s/defn either-output-int-success :- EitherIntOrStr
  []
  1)

(s/defn either-output-str-success :- EitherIntOrStr
  []
  "hi")

(s/defn either-output-keyword-failure :- EitherIntOrStr
  []
  :bad)

(s/defn if-output-int-success :- IfIntOrStr
  []
  1)

(s/defn if-output-str-success :- IfIntOrStr
  []
  "hi")

(s/defn if-output-keyword-failure :- IfIntOrStr
  []
  :bad)

(s/defn both-any-int-output-success :- BothAnyInt
  []
  1)

(s/defn both-int-str-output-int-failure :- BothIntStr
  []
  1)

(s/defn both-int-str-output-str-failure :- BothIntStr
  []
  "hi")

(s/defschema PosInt 
  (s/constrained s/Int pos?))

(s/defschema NonEmptyStr
  (s/constrained s/Str #(not= "" %)))

(s/defschema HasA
  {(s/required-key :a) PosInt})

(s/defschema HasB
  {(s/required-key :b) NonEmptyStr})

(s/defschema HasAOrB
  (s/conditional #(contains? % :a) HasA
                 #(contains? % :b) HasB))

(s/defn takes-has-a :- HasA
  [x :- HasA]
  x)

(s/defn takes-has-b :- HasB
  [x :- HasB]
  x)

(s/defn conditional-map-cond-thread-success :- HasAOrB
  [x :- HasAOrB]
  (cond-> x
    (contains? x :a) takes-has-a
    (contains? x :b) takes-has-b))

(s/defn mk-ab :- HasAOrB
  [x]
  (cond-> {}
    (integer? x) (assoc :a x)
    (not (integer? x)) (assoc :b x)))

(defn mk-ab-int-success
  []
  (mk-ab 1))

(defn mk-ab-str-success
  []
  (mk-ab "hello"))

(s/defn mk-ab-int-returns-ab :- HasAOrB
  []
  (mk-ab 1))

(s/defn mk-ab-str-returns-ab :- HasAOrB
  []
  (mk-ab "hello"))

(s/defn conditional-map-if-a-success :- HasAOrB
  [x :- HasAOrB]
  (if (contains? x :a)
    (takes-has-a x)
    x))

(s/defn conditional-map-if-b-success :- HasAOrB
  [x :- HasAOrB]
  (if (contains? x :b)
    (takes-has-b x)
    x))

(s/defn conditional-map-if-a-bad-branch :- HasAOrB
  [x :- HasAOrB]
  (if (contains? x :a)
    (takes-has-b x)
    x))

(s/defn conditional-map-if-b-bad-branch :- HasAOrB
  [x :- HasAOrB]
  (if (contains? x :b)
    (takes-has-a x)
    x))

(s/defn conditional-map-alias-success :- HasAOrB
  [x :- HasAOrB]
  (let [y x]
    (if (contains? x :a)
      (takes-has-a y)
      y)))

(s/defschema MaybeHasA
  {(s/optional-key :a) s/Int})

(s/defn optional-map-contains-does-not-refine
  [x :- MaybeHasA]
  (if (contains? x :a)
    (takes-has-a x)
    x))

(s/defschema NestedHasAOrB
  {:m (s/maybe HasAOrB)})

(s/defn takes-a-or-b
  [{{:keys [a b]} :m} :- NestedHasAOrB]
  (or a b))

(s/defn mk-nested-ab :- NestedHasAOrB
  [ab]
  {:m (when ab (mk-ab ab))})

(s/defn mk-takes-a-or-b-success-int
  []
  (takes-a-or-b (mk-nested-ab 1)))

(s/defn mk-takes-a-or-b-success-str
  []
  (takes-a-or-b (mk-nested-ab "hello")))

(s/defn mk-takes-a-or-b-success-nil
  []
  (takes-a-or-b (mk-nested-ab nil)))

(s/defn mk-takes-a-or-b-failure-outer
  []
  (takes-a-or-b {:a :nope}))

(s/defn mk-takes-a-or-b-failure-inner
  []
  (takes-a-or-b {:c {:d :nope}}))

(s/defn mk-takes-a-or-b-failure-inner-inner
  []
  (takes-a-or-b {:c {:a :nope}}))

(defn format-hello-map-success
  []
  (format "Hello %s" {:a 1 :b 2}))

(s/defn test-eq-nil :- (s/eq nil)
  []
  (doseq [x [1 2 3]] (println x)))

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
    (when x (take-val x))))

(s/defn when-and-some?-success :- (s/maybe s/Int)
  []
  (let [x nil y 2]
    (when (and y (some? x)) (take-val x))))

(s/defn takes-maybe-constrained :- (s/maybe (s/constrained s/Int pos?))
  [x :- (s/maybe (s/constrained s/Int pos?))]
  x)

(defn nil-satisfies-maybe-constrained-success
  []
  (takes-maybe-constrained nil))

(s/defn self-test :- HasAOrB
  [x :- HasAOrB]
  x)

(s/defn conditional-test :- HasAOrB
  [{:keys [a] :as x} :- HasAOrB]
  (if a
    x 
    (self-test x)))

(s/defn nested-self-test :- NestedHasAOrB
  [x :- NestedHasAOrB]
  x)

(s/defn nested-conditional-test :- NestedHasAOrB
  [{{:keys [a]} :m :as x}]
  (cond-> x 
    a 
    (assoc x :a a)))

(s/defn self-test-success 
  []
  (self-test (mk-ab 1)))

(s/defn nested-self-test-success 
  []
  (nested-self-test (mk-nested-ab "hello")))

(s/defn conditional-test-success-a
  [] 
  (conditional-test (mk-ab 1)))

(s/defn conditional-test-success-b
  [] 
  (conditional-test (mk-ab "hello")))

(s/defn nested-conditional-test-success-a
  [] 
  (nested-conditional-test (mk-nested-ab 1)))

(s/defn nested-conditional-test-success-b
  []
  (nested-conditional-test (mk-nested-ab "hello")))

(s/defn a-dissoc :- [{:a s/Int}]
  []
  (let [base {:a 1 :b 2 :c 3 :d 4 :e 5}]
    [(dissoc base :b :c :d :e)]))

(s/defn abcde-maps :- [{:a s/Int :b s/Int :c s/Int :d s/Int :e s/Int}]
  []
  (let [base {:a 1}]
    [(assoc base :b 2 :c 3 :d 4 :e 5)]))

(s/defn abcde-maps-bad :- [{:a s/Int :b s/Int :c s/Int :d s/Int :e s/Int}]
  []
  (let [base {:a 1}]
    [(assoc base :b 2 :c 3 :d 4 :e "oops")]))