(ns skeptic.local-resolution-shadow
  "Plain `defn` like sample-let-bad-fn so nil args produce real cast errors in check output."
  (:require [skeptic.test-examples.basics :refer [int-add]]))

(defn shadow-provenance
  [x]
  (let [x (int-add 9 9)]
    x)
  (let [y (int-add 1 nil)
        z (int-add 2 3)]
    (int-add x y z)))
