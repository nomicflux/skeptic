(ns skeptic.cljs-fixtures.broken-require
  (:require [no.such.namespace-zzz :as nope]))

(defn use-it [] (nope/f 1))
