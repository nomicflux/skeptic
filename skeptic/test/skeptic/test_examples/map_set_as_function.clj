(ns skeptic.test-examples.map-set-as-function
  (:require [schema.core :as s]))

(s/defn call-kw-to-int :- s/Int
  [f :- (s/=> s/Int s/Keyword)
   k :- s/Keyword]
  (f k))

(s/defn call-str-to-int :- s/Int
  [f :- (s/=> s/Int s/Str)
   x :- s/Str]
  (f x))

(s/defn call-kw-to-str :- s/Str
  [f :- (s/=> s/Str s/Keyword)
   k :- s/Keyword]
  (f k))

(defn map-as-function-success
  []
  (call-kw-to-int {:a 1 :b 2} :a))

(defn map-as-function-wrong-input-failure
  []
  (call-str-to-int {:a 1 :b 2} "a"))

(defn map-as-function-wrong-output-failure
  []
  (call-kw-to-str {:a 1 :b 2} :a))

(s/defn call-kw-to-maybe-kw :- (s/maybe s/Keyword)
  [f :- (s/=> (s/maybe s/Keyword) s/Keyword)
   k :- s/Keyword]
  (f k))

(s/defn call-str-to-maybe-kw :- (s/maybe s/Keyword)
  [f :- (s/=> (s/maybe s/Keyword) s/Str)
   x :- s/Str]
  (f x))

(s/defn call-kw-to-maybe-str :- (s/maybe s/Str)
  [f :- (s/=> (s/maybe s/Str) s/Keyword)
   k :- s/Keyword]
  (f k))

(s/defn call-kw-to-kw :- s/Keyword
  [f :- (s/=> s/Keyword s/Keyword)
   k :- s/Keyword]
  (f k))

(defn set-as-function-success
  []
  (call-kw-to-maybe-kw #{:a :b} :a))

(defn set-as-function-wrong-input-failure
  []
  (call-str-to-maybe-kw #{:a :b} "a"))

(defn set-as-function-wrong-output-failure
  []
  (call-kw-to-maybe-str #{:a :b} :a))

(defn set-as-function-non-nullable-output-failure
  []
  (call-kw-to-kw #{:a :b} :a))
