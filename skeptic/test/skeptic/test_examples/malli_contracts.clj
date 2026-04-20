(ns skeptic.test-examples.malli-contracts)

(defn ^{:malli/schema [:=> [:cat [:maybe :int]] :int]} takes-maybe-int
  [x] (or x 0))

(defn ^{:malli/schema [:=> [:cat :int] :int]} maybe-caller-success
  [] (takes-maybe-int nil))

(defn ^{:malli/schema [:=> [:cat [:maybe :int]] :int]} maybe-output-bad
  [_x] "not-an-int")
