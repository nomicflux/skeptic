(ns p4
  (:require-macros [schema.core :as s])
  (:require [schema.core :as s :include-macros true]))

(s/def my-int :- s/Int 42)

(s/defschema MySchema {:a s/Int})

(s/defn f :- s/Int [x :- s/Int] (+ x 1))

(s/defn g [x :- s/Int] (+ x 1))

(s/defn h :- s/Str [x :- s/Int y :- s/Int] (str x y))

(s/defn k :- s/Int [x :- s/Int & ys :- [s/Int]] (apply + x ys))
