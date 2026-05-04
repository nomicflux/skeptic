(ns skeptic.analysis.cast.dispatch-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as sut]
            [skeptic.analysis.cast.result :as cast-result]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'test-sym 'skeptic.test nil))

(defn T
  [schema]
  (ab/schema->type tp schema))

(deftest dispatch-precedence-and-root-rules-test
  (let [exact (sut/check-cast (T s/Int) (T s/Int))
        target-dyn (sut/check-cast (T s/Int) (T s/Any))
        target-union (sut/check-cast (T s/Int) (T (s/either s/Int s/Str)))
        source-union (sut/check-cast (T (s/either s/Int s/Str)) (T s/Int))
        target-intersection (sut/check-cast (T s/Int) (T (s/both s/Any s/Int)))
        maybe-source (sut/check-cast (T (s/maybe s/Any)) (T s/Int))
        vector-target (sut/check-cast (T [(s/one s/Any 'a) (s/one s/Any 'b)]) (T [s/Int]))
        bottom-source (sut/check-cast (at/BottomType tp) (T s/Int))]
    (is (= :exact (:rule exact)))
    (is (= :target-dyn (:rule target-dyn)))
    (is (= :target-union (:rule target-union)))
    (is (= :source-union (:rule source-union)))
    (is (= :target-intersection (:rule target-intersection)))
    (is (= :maybe-source (:rule maybe-source)))
    (is (= :vector (:rule vector-target)))
    (is (= :bottom-source (:rule bottom-source)))))

(deftest function-polarity-and-method-selection-test
  (let [domain-failure (sut/check-cast (T (s/=> s/Int s/Int))
                                       (T (s/=> s/Int s/Str)))
        domain-child (first (-> domain-failure :children first :children))
        range-failure (sut/check-cast (T (s/=> s/Str s/Int))
                                      (T (s/=> s/Int s/Int)))
        range-child (last (-> range-failure :children first :children))]
    (is (= :function (:rule domain-failure)))
    (is (= :negative (:blame-polarity domain-child)))
    (is (= :function (:rule range-failure)))
    (is (= :positive (:blame-polarity range-child)))))

(deftest function-arity-diagnostic-preserves-target-method-test
  (let [source (T (s/=> s/Any))
        target (T (s/=> s/Any s/Str))
        [leaf] (cast-result/leaf-diagnostics (sut/check-cast source target))]
    (is (= :function-arity (:rule leaf)))
    (is (= :arity-mismatch (:reason leaf)))
    (is (some? (:expected-type leaf)))
    (is (at/fn-method-type? (:expected-type leaf)))))

(deftest maybe-to-eq-nil-cast-test
  (let [ok (sut/check-cast (T (s/maybe s/Any)) (T (s/eq nil)))
        bad (sut/check-cast (T (s/maybe s/Int)) (T (s/eq nil)))]
    (is (true? (:ok? ok)))
    (is (= :maybe-to-eq-nil (:rule ok)))
    (is (false? (:ok? bad)))))

(deftest result-tree-contract-test
  (let [result (sut/check-cast (T {:user {:name s/Keyword}})
                               (T {:user {:name s/Str}}))]
    (is (false? (:ok? result)))
    (is (vector? (:children result)))
    (is (every? #(contains? result %) [:ok?
                                       :blame-side
                                       :blame-polarity
                                       :rule
                                       :source-type
                                       :target-type
                                       :children
                                       :reason]))))
