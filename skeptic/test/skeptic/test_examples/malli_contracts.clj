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

(defn ^{:malli/schema [:=> [:cat :int] [:and :int :int]]} and-output-success
  [x] x)

(defn ^{:malli/schema [:=> [:cat :int] [:and :int :int]]} and-output-bad
  [_x] :not-an-int)

(defn ^{:malli/schema [:=> [:cat :int :string] [:tuple :int :string]]} tuple-output-success
  [x y] [x y])

(defn ^{:malli/schema [:=> [:cat :int :string] [:tuple :int :string]]} tuple-output-bad-element
  [x _y] [x :not-a-string])

(defn ^{:malli/schema [:=> [:cat :int :string] [:tuple :int :string]]} tuple-output-bad-arity
  [x y] [x y :extra])

(defn ^{:malli/schema [:=> [:cat :int :string] [:map [:x :int] [:y :string]]]} map-output-success
  [x y] {:x x :y y})

(defn ^{:malli/schema [:=> [:cat :int :string] [:map [:x :int] [:y :string]]]} map-output-bad-value
  [x _y] {:x x :y :not-a-string})

(defn ^{:malli/schema [:=> [:cat :int] [:map [:x :int] [:y :string]]]} map-output-missing-key
  [x] {:x x})

(defn ^{:malli/schema [:=> [:cat :int] [:map [:x :int] [:y {:optional true} :string]]]} map-output-optional-key-omitted
  [x] {:x x})

(defn ^{:malli/schema [:=> [:cat :int :string] [:map [:x :int] [:y {:optional true} :string]]]} map-output-optional-key-present
  [x y] {:x x :y y})

(defn ^{:malli/schema [:=> [:cat :int]
                       [:multi {:dispatch :tag}
                        [:a [:map [:tag [:= :a]] [:value :int]]]
                        [:b [:map [:tag [:= :b]] [:value :string]]]]]}
  multi-output-success-a
  [x] {:tag :a :value x})

(defn ^{:malli/schema [:=> [:cat :string]
                       [:multi {:dispatch :tag}
                        [:a [:map [:tag [:= :a]] [:value :int]]]
                        [:b [:map [:tag [:= :b]] [:value :string]]]]]}
  multi-output-success-b
  [s] {:tag :b :value s})

(defn ^{:malli/schema [:=> [:cat :int]
                       [:multi {:dispatch :tag}
                        [:a [:map [:tag [:= :a]] [:value :int]]]
                        [:b [:map [:tag [:= :b]] [:value :string]]]]]}
  multi-output-bad-value
  [_x] {:tag :a :value :not-an-int})

(defn ^{:malli/schema [:=> [:cat :int]
                       [:multi {:dispatch :tag}
                        [:a [:map [:tag [:= :a]] [:value :int]]]
                        [:malli.core/default [:map [:value :keyword]]]]]}
  multi-output-default-branch
  [_x] {:value :anything})

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

(defn ^{:malli/schema [:=> [:cat :double] :double]} takes-double
  [x] x)

(defn ^{:malli/schema [:=> [:cat :int] :double]} double-input-rejects-string
  [] (takes-double "hello"))

(defn ^{:malli/schema [:=> [:cat :float] :float]} takes-float
  [x] x)

(defn ^{:malli/schema [:=> [:cat :int] :float]} float-input-rejects-string
  [] (takes-float "hello"))

(defn ^{:malli/schema [:=> [:cat :int]
                       [:schema {:registry {::int :int}} [:ref ::int]]]}
  ref-int-output-success
  [x] x)

(defn ^{:malli/schema [:=> [:cat :int]
                       [:schema {:registry {::int :int}} [:ref ::int]]]}
  ref-int-output-bad-value
  [_x] :not-an-int)

(defn ^{:malli/schema [:=> [:cat :int]
                       [:schema {:registry {::point [:map [:x :int] [:y :int]]}}
                        [:ref ::point]]]}
  ref-map-output-success
  [x] {:x x :y x})

(defn ^{:malli/schema [:=> [:cat :int]
                       [:schema {:registry {::point [:map [:x :int] [:y :int]]}}
                        [:ref ::point]]]}
  ref-map-output-missing-key
  [x] {:x x})

(defn ^{:malli/schema [:=> [:cat]
                       [:schema {:registry {::ints [:maybe [:tuple :int [:ref ::ints]]]}}
                        [:ref ::ints]]]}
  recursive-ref-output-nil
  [] nil)
