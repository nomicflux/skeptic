(ns skeptic.cljs-fixtures.p10-var-quote.dep
  (:require [schema.core :as s]))

(s/defn parse :- s/Str
  [form :- s/Str]
  form)
