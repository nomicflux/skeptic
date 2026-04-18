(ns skeptic.analysis.cast.result-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as cast]
            [skeptic.analysis.cast.result :as sut]))

(defn T [schema] (ab/schema->type schema))

(def HasA
  {(s/required-key :a) s/Int})

(def HasB
  {(s/required-key :b) s/Str})

(def HasAOrB
  (s/conditional #(contains? % :a) HasA
                 #(contains? % :b) HasB))

(deftest ok?-test
  (is (sut/ok? (cast/check-cast (T s/Int) (T s/Int))))
  (is (sut/ok? (cast/check-cast (T s/Int) (T s/Any))))
  (is (not (sut/ok? (cast/check-cast (T s/Int) (T s/Str)))))
  (is (not (sut/ok? (cast/check-cast (T {:a s/Int}) (T {:a s/Str}))))))

(deftest root-summary-test
  (let [ok (cast/check-cast (T s/Int) (T s/Int))
        fail (cast/check-cast (T s/Str) (T s/Int))]
    (is (= {:ok? true :rule :exact :blame-side :none :blame-polarity :none
            :actual-type (T s/Int) :expected-type (T s/Int)}
           (sut/root-summary ok)))
    (is (false? (:ok? (sut/root-summary fail))))
    (is (= :leaf-overlap (:rule (sut/root-summary fail))))
    (is (= (T s/Str) (:actual-type (sut/root-summary fail))))
    (is (= (T s/Int) (:expected-type (sut/root-summary fail))))))

(deftest leaf-diagnostics-flattens-nested-map-test
  (let [raw (cast/check-cast (T {:user {:name s/Keyword}})
                             (T {:user {:name s/Str}}))
        leaves (sut/leaf-diagnostics raw)]
    (is (= 1 (count leaves)))
    (is (= :leaf-mismatch (:reason (first leaves))))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (:path (first leaves))))
    (is (= (T s/Keyword) (:actual-type (first leaves))))
    (is (= (T s/Str) (:expected-type (first leaves))))))

(deftest leaf-diagnostics-returns-empty-on-success-test
  (is (= [] (sut/leaf-diagnostics (cast/check-cast (T s/Int) (T s/Int))))))

(deftest leaf-diagnostics-preserves-blame-fields-test
  (let [[leaf] (sut/leaf-diagnostics (cast/check-cast (T s/Str) (T s/Int)))]
    (is (= :term (:blame-side leaf)))
    (is (= :positive (:blame-polarity leaf)))))

(deftest leaf-diagnostics-preserves-key-fields-test
  (let [raw (cast/check-cast (T {:a s/Int}) (T {:a s/Int :b s/Str}))
        leaves (sut/leaf-diagnostics raw)
        missing (first (filter #(= :missing-key (:reason %)) leaves))]
    (is (some? missing))
    (is (contains? missing :expected-key))))

(deftest leaf-diagnostics-root-failure-returns-single-test
  (let [raw (cast/check-cast (T s/Str) (T s/Int))
        leaves (sut/leaf-diagnostics raw)]
    (is (= 1 (count leaves)))
    (is (= :leaf-mismatch (:reason (first leaves))))))

(deftest leaf-diagnostics-projects-failed-source-conditional-at-aggregate-level
  (let [raw (cast/check-cast (T HasAOrB) (T HasA))
        leaves (sut/leaf-diagnostics raw)
        [leaf] leaves]
    (is (= 1 (count leaves)))
    (is (= :source-union (:rule leaf)))
    (is (= :source-branch-failed (:reason leaf)))
    (is (= [] (:path leaf)))
    (is (= (T HasAOrB) (:actual-type leaf)))
    (is (= (T HasA) (:expected-type leaf)))))

(deftest leaf-diagnostics-projects-failed-source-intersection-at-aggregate-level
  (let [raw (cast/check-cast (T (s/both s/Int s/Str)) (T s/Keyword))
        leaves (sut/leaf-diagnostics raw)
        [leaf] leaves]
    (is (= 1 (count leaves)))
    (is (= :source-intersection (:rule leaf)))
    (is (= :source-component-failed (:reason leaf)))
    (is (= [] (:path leaf)))
    (is (= (T (s/both s/Int s/Str)) (:actual-type leaf)))
    (is (= (T s/Keyword) (:expected-type leaf)))))

(deftest primary-diagnostic-returns-first-leaf-test
  (let [raw (cast/check-cast (T {:user {:name s/Keyword}})
                             (T {:user {:name s/Str}}))
        primary (sut/primary-diagnostic raw)]
    (is (= :leaf-mismatch (:reason primary)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (:path primary)))))

(deftest primary-diagnostic-falls-back-to-root-on-success-test
  (let [raw (cast/check-cast (T s/Int) (T s/Int))
        primary (sut/primary-diagnostic raw)]
    (is (= :exact (:rule primary)))
    (is (true? (:ok? (sut/root-summary raw))))))

(deftest source-key-domain-preserved-in-diagnostics-test
  (let [raw (cast/check-cast (T {s/Keyword s/Int}) (T {:a s/Int :b s/Int}))
        leaves (sut/leaf-diagnostics raw)
        domain-leaf (first (filter #(= :map-key-domain-not-covered (:reason %)) leaves))]
    (is (some? domain-leaf))
    (is (contains? domain-leaf :source-key-domain))))

(deftest conditional-types-work-under-maybe-cast-test
  (let [source (T {:x s/Int})
        target (T (s/maybe (s/conditional :x {:x s/Int})))
        result (cast/check-cast source target)
        source-conditional (T (s/maybe (s/conditional :x {:x s/Int})))
        target-map (T (s/maybe {:x s/Int}))]
    (is (sut/ok? result))
    (is (= :maybe-target (:rule result)))
    (is (sut/ok? (cast/check-cast source-conditional target-map)))))
