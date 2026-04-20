(ns skeptic.test-examples.malli-contracts)

(defn ^{:malli/schema [:=> [:cat [:maybe :int]] :int]} takes-maybe-int
  [x] (or x 0))

(defn ^{:malli/schema [:=> [:cat :int] :int]} maybe-caller-success
  [] (takes-maybe-int nil))

(defn ^{:malli/schema [:=> [:cat [:maybe :int]] :int]} maybe-output-bad
  [_x] "not-an-int")

(defn ^{:malli/schema [:=> [:cat :int] [:or :int :string]]} or-output-success
  [x] x)

(defn ^{:malli/schema [:=> [:cat :int] [:or :int :string]]} or-output-bad
  [_x] :not-a-string-or-int)
