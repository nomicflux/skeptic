(ns skeptic.typed-decls.malli-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.types :as at]
            [skeptic.schema.collect :as scollect]
            [skeptic.typed-decls.malli :as tdm]))

(def ^:private Int (at/->GroundT :int 'Int))

(deftest desc->typed-entry-callable
  (let [entry (tdm/desc->typed-entry
               {:name 'foo/bar
                :malli-spec [:=> [:cat :int :int] :int]})]
    (is (= 'foo/bar (:name entry)))
    (is (= 1 (count (:typings entry))))
    (is (at/fun-type? (first (:typings entry))))
    (is (= Int (:output-type entry)))
    (let [arglists (:arglists entry)
          arity-2 (get arglists 2)]
      (is (= #{2} (set (keys arglists))))
      (is (= 2 (:count arity-2)))
      (is (= [Int Int] (mapv :type (:types arity-2))))
      (is (= 2 (count (:arglist arity-2)))))))

(deftest desc->typed-entry-non-callable
  (let [entry (tdm/desc->typed-entry
               {:name 'foo/baz
                :malli-spec :int})]
    (is (= 'foo/baz (:name entry)))
    (is (= 1 (count (:typings entry))))
    (is (not (contains? entry :output-type)))
    (is (not (contains? entry :arglists)))))

(deftest typed-ns-malli-results-entries
  (let [{:keys [entries errors]} (tdm/typed-ns-malli-results {} 'skeptic.test-examples.malli)]
    (is (empty? errors))
    (is (contains? entries 'skeptic.test-examples.malli/demo-fn))
    (let [entry (get entries 'skeptic.test-examples.malli/demo-fn)]
      (is (at/fun-type? (first (:typings entry))))
      (is (= Int (:output-type entry)))
      (is (= [Int] (mapv :type (:types (get (:arglists entry) 1))))))))

(deftest malli-declaration-error-shape
  (testing "shared declaration-error-result phased for malli"
    (let [err (scollect/declaration-error-result
               :malli-declaration 'foo 'foo/bar nil (ex-info "boom" {}))]
      (is (= :malli-declaration (:phase err)))
      (is (= 'foo/bar (:blame err)))
      (is (= 'foo (:namespace err)))
      (is (= :exception (:report-kind err))))))
