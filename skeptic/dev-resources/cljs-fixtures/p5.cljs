(ns p5
  (:require-macros [malli.core :as m])
  (:require [malli.core :as m :include-macros true]))

(defn g [x] (+ x 1))
(m/=> g [:=> [:cat :int] :int])

(defn h
  {:malli/schema [:=> [:cat :int] :int]}
  [x]
  (+ x 1))

(def default-key ::m/default)

(def js-value #js {:a 1})
