(ns skeptic.test-examples.origin-cases
  "Fixtures for origin-test. Each former `(analyze-form '(form) {:locals ...})`
   probe becomes a real `s/defn` whose parameters supply the former injected
   locals with their declared schemas, so the worker analyzes the body with
   those locals in scope. The origin-test selects the relevant inner node by
   form/op and asserts the same origin/type/assumption shape as before."
  (:require [schema.core :as s]
            [skeptic.test-examples.basics :as basics]
            [skeptic.test-examples.nullability :as nullability]))

;; call-arg-contract-assumptions-test
(s/defn oc-plus-x-1 [x :- s/Any] (+ x 1))
(s/defn oc-str-x [x :- s/Any] (str x))

;; typed-binding-and-refinement-test / attach-type-branch-refinement-test
(s/defn oc-or-let []
  (let [y nil
        x (or y 1)]
    (basics/int-add x 2)))
(s/defn oc-literal-if [] (if (even? 2) true "hello"))
(s/defn oc-local-if [x :- s/Any] (if (pos? x) 1 -1))
(s/defn oc-maybe-if [] (let [x nil] (if x x 1)))
(s/defn oc-or-form [] (or nil 1))

;; region-conjuncts-and-shape-two-some-test
(s/defn oc-and-some [x :- (s/maybe s/Int) y :- (s/maybe s/Str)]
  (and (some? x) (some? y)))

;; region-conjuncts-and-shape-emits-disjunction
(s/defn oc-and-pos [x :- s/Int y :- s/Int]
  (and (pos? x) (pos? y)))

;; equality-test-assumptions
(s/defn oc-eq-local-literal [x :- (s/enum :a :b)] (= x :a))
(s/defn oc-eq-literal-local [x :- (s/enum :a :b)] (= :a x))

;; let-shadow-nil-check-root-origin-some-to-lambda-shape-test
(s/defn oc-shadow-nil-check [input :- (s/maybe s/Num)]
  (let [x input
        x (if (nil? x) nil (nullability/non-null-transform x))]
    (if (nil? x) nil (#(- %) x))))

;; negated-assumptions-and-narrowing-alias-roots-test
(s/defn oc-not-nil-if [x :- (s/maybe s/Str)]
  (if (not (nil? x)) x "fallback"))
(s/defn oc-narrowing-alias [input :- (s/maybe s/Str)]
  (let [raw input
        p (when (some? raw) raw)]
    (if (some? p) p nil)))

;; chained-keyword-invoke-yields-path-origin
(s/defn oc-chained-kw [x :- {:x {:k s/Str}}] (:k (:x x)))

;; destructured-projection-binding-origin
(s/defn oc-destructure-projection [x :- {:x {:k s/Str}}]
  (let [{:keys [k]} (:x x)] k))

;; destructure-as-alias-preserves-root-origin
(s/defn oc-destructure-as-alias [input :- {:x {:k s/Str}}]
  (let [{{:keys [k]} :x :as x} input] x))

;; nested-destructure-double-shim-yields-full-path
(s/defn oc-nested-destructure [input :- {:x {:inner {:k s/Str}}}]
  (let [{{{:keys [k]} :inner} :x :as x} input] k))

;; static-get-with-default-yields-path-origin
(s/defn oc-static-get-default [x :- {:x {:k s/Str}}]
  (let [g (clojure.core/get x :x nil)
        k (clojure.core/get g :k nil)]
    k))

;; equality-value-assumption-path-shape
(s/defn oc-eq-path [x :- {:x {:k s/Str}}]
  (let [{:keys [k]} (:x x)] (= k "b")))

;; do-forwards-ret-origin-test / try-* / with-meta-forwards-expr-origin-test
(s/defn oc-do-forwards [some-local :- s/Str]
  (do (println :x) some-local))
(s/defn oc-try-finally [some-local :- s/Str]
  (try some-local (finally (println :cleanup))))
(s/defn oc-try-catch [some-local :- s/Str]
  (try some-local (catch Exception _ :default)))
(s/defn oc-with-meta [some-local :- s/Str]
  ^{:foo 1} some-local)
