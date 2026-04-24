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

(defn ^{:malli/schema [:=> [:cat [:maybe :int]] [:or :int :string]]} combined-success
  [x] (or x "fallback"))

(defn ^{:malli/schema [:=> [:cat [:maybe :int]] [:or :int :string]]} combined-bad
  [_x] :bad-keyword)

(def ^{:malli/schema [:map [:x :int]]} map-schema-dyn-var {:x 1})

(defn ^{:malli/schema [:=> [:cat :int] :int]} map-dyn-caller
  [n] (get map-schema-dyn-var :x n))

(defn ^{:malli/schema [:=> [:cat :int] [:enum :ok :bad]]} enum-output-success
  [_x] :ok)

(defn ^{:malli/schema [:=> [:cat [:enum :ok :bad]] :string]} enum-input-flows-to-string
  [x] x)
