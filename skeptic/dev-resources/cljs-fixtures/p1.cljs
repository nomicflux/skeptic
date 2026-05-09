(ns p1
  (:require-macros [schema.core :as s])
  (:require [schema.core :as s :include-macros true]))

(s/defn f :- s/Int [x :- s/Int] (+ x 1))
