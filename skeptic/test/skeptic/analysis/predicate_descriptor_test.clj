(ns skeptic.analysis.predicate-descriptor-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.predicate-descriptor :as pd]))

(def ^:private classifier-summary
  {:kind :unary-map-classifier
   :path [{:value :k}]
   :cases {"a" :a "b" :b}
   :default :unclassified})

(def ^:private summaries
  {'foo.ns/my-classifier classifier-summary})

(deftest comp-single-lit-known-classifier
  (let [d (pd/predicate-form->descriptor '(comp #{:b} my-classifier) 'foo.ns summaries)]
    (is (= [{:value :k}] (:path d)))
    (is (= ["b"] (:values d)))))

(deftest comp-multi-lit-known-classifier
  (let [d (pd/predicate-form->descriptor '(comp #{:a :b} my-classifier) 'foo.ns summaries)]
    (is (= #{"a" "b"} (set (:values d))))))

(deftest comp-fully-qualified-classifier
  (let [d (pd/predicate-form->descriptor '(comp #{:b} foo.ns/my-classifier) 'other.ns summaries)]
    (is (= ["b"] (:values d)))))

(deftest comp-unknown-classifier
  (is (nil? (pd/predicate-form->descriptor '(comp #{:b} nonexistent) 'foo.ns summaries))))

(deftest comp-non-classifier-summary
  (let [accessor-summaries {'foo.ns/k-getter {:kind :unary-map-accessor :kw :k}}]
    (is (nil? (pd/predicate-form->descriptor '(comp #{:b} k-getter) 'foo.ns accessor-summaries)))))

(deftest non-comp-shape-returns-nil
  (is (nil? (pd/predicate-form->descriptor '(fn [v] (= :b v)) 'foo.ns summaries)))
  (is (nil? (pd/predicate-form->descriptor 'some-pred 'foo.ns summaries)))
  (is (nil? (pd/predicate-form->descriptor nil 'foo.ns summaries))))
