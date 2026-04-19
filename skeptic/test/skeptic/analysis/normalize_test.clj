(ns skeptic.analysis.normalize-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.normalize :as an]
            [skeptic.test-examples.resolution]
            [skeptic.typed-decls :as typed-decls]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest declaration-index-contract-test
  (let [dict (typed-decls/typed-ns-entries {} 'skeptic.test-examples.resolution)
        forward-entry (an/normalize-entry (get dict 'skeptic.test-examples.resolution/forward-declared-target))
        recursive-entry (an/normalize-entry (get dict 'skeptic.test-examples.resolution/self-recursive-identity))]
    (is (= [(T s/Any)]
           (-> forward-entry :arglists (get 1) :types (->> (mapv :type)))))
    (is (= (T s/Any) (:output-type forward-entry)))
    (is (= [(T s/Any)]
           (-> recursive-entry :arglists (get 1) :types (->> (mapv :type)))))
    (is (= (T s/Any) (:output-type recursive-entry)))))

(deftest normalize-entry-rejects-raw-schema-only-entries
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected typed entry"
                        (an/normalize-entry {:schema (s/make-fn-schema s/Int [[]])})))
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected typed arg entry"
                        (an/normalize-arg-entry {:schema s/Int}))))

(deftest normalize-entry-defaults-typed-partial-entries
  (let [entry (an/normalize-entry {:output-type (T s/Int)
                                   :arglists {1 {:arglist ['x]
                                                 :types [{:name 'x
                                                          :type (T s/Int)}]}}})]
    (testing "missing top-level type defaults to Dyn"
      (is (= (T s/Any) (:type entry))))
    (testing "missing count and optional? are defaulted"
      (is (= 1 (get-in entry [:arglists 1 :count])))
      (is (= [{:name 'x
               :optional? false
               :type (T s/Int)}]
             (get-in entry [:arglists 1 :types]))))
    (testing "semantic types are preserved rather than re-imported"
      (is (= (T s/Int) (:output-type entry))))))
