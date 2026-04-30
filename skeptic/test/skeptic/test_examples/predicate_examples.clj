(ns skeptic.test-examples.predicate-examples
  (:require [schema.core :as s]))

(s/defn schema-pred-string-input-success :- s/Str
  [x :- (s/pred string?)]
  (str x "!"))

(s/defn schema-pred-pos-input-success :- s/Num
  [n :- (s/pred pos?)]
  (+ n 1))

(s/defn schema-pred-nil-input-success :- (s/eq nil)
  [x :- (s/pred nil?)]
  x)

(defn ^{:malli/schema [:=> [:cat string?] :boolean]} malli-string-pred-input-success
  [x] (string? x))

(defn ^{:malli/schema [:=> [:cat int?] :int]} malli-int-pred-input-success
  [n] (inc n))

(s/defn narrow-via-call-keyword-fn
  [x :- s/Keyword]
  x)

(s/defn narrow-via-call-keyword-failure
  [x :- s/Any]
  (let [_ (narrow-via-call-keyword-fn x)]
    (+ x 1)))

(s/defn narrow-via-static-plus-number-failure
  [x :- s/Any]
  (let [_ (+ x 1)]
    (narrow-via-call-keyword-fn x)))

(s/defn narrow-via-call-contract-next-binding-failure
  [x :- s/Any]
  (let [_ (narrow-via-call-keyword-fn x)
        y (+ x 1)]
    y))

(s/defn narrow-via-let-bound-pred-keyword-failure
  [x :- s/Any]
  (let [y (keyword? x)]
    (when y
      (+ x 1))))
