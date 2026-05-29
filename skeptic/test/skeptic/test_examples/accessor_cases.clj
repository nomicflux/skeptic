(ns skeptic.test-examples.accessor-cases
  "Classifier/accessor-helper fixtures for accessor-summary-test. Each former
   `(analyze-form '(defn classify [m] ...))` probe becomes a real def the worker
   analyzes; the test reads its accessor `:summary` via
   `pipeline/analyzed-def-entry`.")

(defn ac-classify-kw-invoke [m]
  (case (:k m) "a" :a "b" :b :unclassified))

(defn ac-classify-static-get [m]
  (case (clojure.lang.RT/get m :k) "a" :a "b" :b :unclassified))

(defn ac-classify-destructured [{:keys [k]}]
  (case k "a" :a "b" :b :unclassified))

(defn ac-classify-plain-get [m]
  (case (get m :k) "a" :a "b" :b :unclassified))

(defn ac-choose-kw-get-default [m]
  (keyword (get m :k :a)))

(defn ac-another-classifier [m]
  (case (:k m) "a" :a "b" :b :unclassified))

(defn ac-k-get [m] (:k m))
