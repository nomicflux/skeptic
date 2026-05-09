(ns skeptic.cljs-fixtures.p7.bar
  (:require-macros [schema.core :as s])
  (:require [schema.core :as s :include-macros true]))

(s/defn add-int :- s/Int [x :- s/Int] (+ x 1))

(s/defn caller [] (add-int "not-int"))
