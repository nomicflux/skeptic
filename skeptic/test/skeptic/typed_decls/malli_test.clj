(ns skeptic.typed-decls.malli-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.types :as at]
            [skeptic.schema.collect :as scollect]
            [skeptic.typed-decls.malli :as tdm]))

(def ^:private Int (at/->GroundT :int 'Int))

(deftest desc->type-callable-returns-fun-type
  (let [t (tdm/desc->type
            {:name 'foo/bar
             :malli-spec [:=> [:cat :int :int] :int]})]
    (is (at/fun-type? t))
    (is (= 1 (count (at/fun-methods t))))
    (is (= [Int Int] (at/fn-method-inputs (first (at/fun-methods t)))))
    (is (= Int (at/fn-method-output (first (at/fun-methods t)))))
    (is (= 2 (count (at/fn-method-input-names (first (at/fun-methods t))))))))

(deftest desc->type-non-callable-returns-ground-type
  (let [t (tdm/desc->type {:name 'foo/baz :malli-spec :int})]
    (is (= Int t))
    (is (not (at/fun-type? t)))))

(deftest typed-ns-malli-results-entries
  (let [{:keys [dict errors]} (tdm/typed-ns-malli-results {} 'skeptic.test-examples.malli)]
    (is (empty? errors))
    (is (contains? dict 'skeptic.test-examples.malli/demo-fn))
    (let [t (get dict 'skeptic.test-examples.malli/demo-fn)]
      (is (at/fun-type? t))
      (is (= Int (at/fn-method-output (first (at/fun-methods t)))))
      (is (= [Int] (at/fn-method-inputs (first (at/fun-methods t))))))))

(deftest malli-declaration-error-shape
  (testing "shared declaration-error-result phased for malli"
    (let [err (scollect/declaration-error-result
               :malli-declaration 'foo 'foo/bar nil (ex-info "boom" {}))]
      (is (= :malli-declaration (:phase err)))
      (is (= 'foo/bar (:blame err)))
      (is (= 'foo (:namespace err)))
      (is (= :exception (:report-kind err))))))
