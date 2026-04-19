(ns skeptic.test-examples.malli)

(defn ^{:malli/schema [:=> [:cat :int] :int]} demo-fn
  [x]
  x)
