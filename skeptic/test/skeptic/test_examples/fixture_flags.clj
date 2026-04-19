(ns skeptic.test-examples.fixture-flags
  (:require [schema.core :as s]
            [skeptic.test-examples.basics :refer [int-add]]))

(defn ^:always-validate sample-metadata-fn
  {:something-else true}
  [x]
  (int-add x nil))

(defn sample-doc-fn
  "Doc here."
  [x]
  (int-add x nil))

(defn ^:always-validate sample-doc-and-metadata-fn
  "Doc here."
  {:something-else true}
  [x]
  (int-add x nil))

(defn sample-fn-once
  [x]
  ((^{:once true} fn* [y] (int-add y nil))
   x))

(s/defn ignored-body-fn :- s/Int
  {:skeptic/ignore-body true}
  [x :- s/Int]
  (int-add nil x))

(s/defn caller-of-ignored :- s/Int
  [x :- s/Str]
  (ignored-body-fn x))

(s/defn good-caller-of-ignored :- s/Int
  [x :- s/Int]
  (ignored-body-fn x))

(s/defn opaque-fn :- s/Int
  {:skeptic/opaque true}
  [x :- s/Int]
  "not-an-int")

(s/defn caller-of-opaque :- s/Str
  [x :- s/Str]
  (opaque-fn x))

(s/defn str-helper :- s/Str
  []
  "hi")

(s/defn override-fn :- s/Int
  []
  (let [y ^{:skeptic/type s/Int} (str-helper)]
    (int-add y 1)))

(s/defn override-wrong-fn :- s/Int
  []
  (let [y ^{:skeptic/type s/Str} (int-add 1)]
    (int-add y 1)))
