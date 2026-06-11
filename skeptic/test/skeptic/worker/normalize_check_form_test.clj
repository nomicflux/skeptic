(ns skeptic.worker.normalize-check-form-test
  "The worker's s/defn -> defn normalization runs schema.macros' own grammar
   fns. Pins the annotated rest-destructure case the hand-rolled stripping
   missed (plumatic/schema core_test.cljc: [arg0 & [rest0 :- (s/pred pos?)]]),
   plus head and multi-arity shapes."
  (:require [clojure.test :refer [deftest is]]
            [schema.core]
            [skeptic.worker.analyzer-clj :as wac]))

(def ^:private normalize @#'wac/normalize-check-form)

(defn- contains-arrow?
  [form]
  (boolean (some #(= :- %) (tree-seq coll? seq form))))

(defn- analyzed-op
  [form]
  (:op (wac/analyze form {:ns 'skeptic.worker.normalize-check-form-test
                          :locals {}
                          :source-file nil})))

(deftest annotated-rest-destructure-normalizes
  (let [form '(schema.core/defn rest-test-fn :- (schema.core/pred even?)
                [arg0 & [rest0 :- (schema.core/pred pos?)]]
                (+ arg0 (or rest0 2)))
        normalized (normalize form)]
    (is (= 'defn (first normalized)))
    (is (not (contains-arrow? normalized)))
    (is (= :def (analyzed-op normalized)))))

(deftest head-docstring-attr-map-and-multi-arity-normalize
  (let [form '(schema.core/defn multi :- schema.core/Int
                "doc"
                {:added "1.0"}
                ([x :- schema.core/Int] (inc x))
                ([x :- schema.core/Int y :- schema.core/Int] (+ x y)))
        normalized (normalize form)]
    (is (= 'defn (first normalized)))
    (is (not (contains-arrow? normalized)))
    (is (= :def (analyzed-op normalized)))))

(deftest aliased-head-normalizes
  (let [normalized (normalize '(s/defn aliased-fn :- s/Int [x :- s/Int] x))]
    (is (= 'defn (first normalized)))
    (is (not (contains-arrow? normalized)))))

(deftest non-schema-forms-pass-through
  (let [form '(defn plain [x] x)]
    (is (identical? form (normalize form)))))
