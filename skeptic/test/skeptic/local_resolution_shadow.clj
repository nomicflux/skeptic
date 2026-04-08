(ns skeptic.local-resolution-shadow
  "Plain `defn` like sample-let-bad-fn so nil args produce real cast errors in check output."
  (:require [skeptic.test-examples :as te]))

(defn shadow-provenance
  [x]
  (let [x (te/int-add 9 9)]
    x)
  (let [y (te/int-add 1 nil)
        z (te/int-add 2 3)]
    (te/int-add x y z)))
