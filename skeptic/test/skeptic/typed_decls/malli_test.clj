(ns skeptic.typed-decls.malli-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.schema.collect :as scollect]
            [skeptic.test-helpers :refer [is-type= tp]]
            [skeptic.typed-decls.malli :as tdm]))

(def ^:private Int (at/->GroundT tp :int 'Int))

(deftest desc->type-callable-returns-fun-type
  (let [t (tdm/desc->type tp {:name 'foo/bar
                              :malli-spec [:=> [:cat :int :int] :int]})]
    (is (at/fun-type? t))
    (is (= 1 (count (at/fun-methods t))))
    (is (= [Int Int] (at/fn-method-inputs (first (at/fun-methods t)))))
    (is (= Int (at/fn-method-output (first (at/fun-methods t)))))
    (is (= 2 (count (at/fn-method-input-names (first (at/fun-methods t))))))))

(deftest desc->type-non-callable-returns-ground-type
  (let [t (tdm/desc->type tp {:name 'foo/baz :malli-spec :int})]
    (is (= Int t))
    (is (not (at/fun-type? t)))))

(deftest desc->type-enum-returns-union-of-exact-values
  (let [t (tdm/desc->type tp {:name 'foo/e :malli-spec [:enum :a :b]})
        expected (ato/union-type tp [(ato/exact-value-type tp :a)
                                     (ato/exact-value-type tp :b)])]
    (is-type= expected t)))

(deftest desc->type-enum-in-=>-output
  (let [t (tdm/desc->type tp {:name 'foo/f :malli-spec [:=> [:cat :int] [:enum :ok :bad]]})
        expected (ato/union-type tp [(ato/exact-value-type tp :ok)
                                     (ato/exact-value-type tp :bad)])]
    (is (at/fun-type? t))
    (is-type= expected (at/fn-method-output (first (at/fun-methods t))))))

(deftest typed-ns-malli-results-entries
  (let [{:keys [dict errors]} (tdm/typed-ns-malli-results {} 'skeptic.test-examples.malli)]
    (is (empty? errors))
    (is (contains? dict 'skeptic.test-examples.malli/demo-fn))
    (let [t (get dict 'skeptic.test-examples.malli/demo-fn)]
      (is (at/fun-type? t))
      (is-type= Int (at/fn-method-output (first (at/fun-methods t))))
      (let [inputs (at/fn-method-inputs (first (at/fun-methods t)))]
        (is (= 1 (count inputs)))
        (is-type= Int (first inputs))))))

(deftest malli-declaration-error-shape
  (testing "shared declaration-error-result phased for malli"
    (let [err (scollect/declaration-error-result
               :malli-declaration 'foo 'foo/bar nil (ex-info "boom" {}))]
      (is (= :malli-declaration (:phase err)))
      (is (= 'foo/bar (:blame err)))
      (is (= 'foo (:namespace err)))
      (is (= :exception (:report-kind err))))))
