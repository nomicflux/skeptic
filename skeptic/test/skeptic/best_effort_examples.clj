(ns skeptic.best-effort-examples
  (:require [schema.core :as s]))

(defn ^{:schema [(Object.)]}
  invalid-schema-decl
  [x]
  x)

(s/defn ok-plus :- s/Int
  [x :- s/Int
   y :- s/Int]
  (+ x y))

(s/defn good-call :- s/Int
  [x :- s/Int]
  (ok-plus x 1))
