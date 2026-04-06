(ns skeptic.check-project-best-effort-examples
  (:require [schema.core :as s]))

(s/defn good-plus :- s/Int
  [x :- s/Int
   y :- s/Int]
  (+ x y))

(s/defn exploding-form :- s/Int
  [x :- s/Int]
  (good-plus x 1))

(s/defn later-mismatch :- s/Int
  [x :- s/Str]
  (good-plus x 1))
