(ns skeptic.clj-fixtures.best-effort.healthy
  (:require [schema.core :as s]))

(s/defn add-int :- s/Int [x :- s/Int] (+ x 1))

(s/defn caller [] (add-int "not-int"))
