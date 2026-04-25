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
