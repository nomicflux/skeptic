(ns skeptic.checking.pipeline.collection-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]))

(deftest abcde-maps-output-type-errors
  (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/abcde-maps)))
  (is (= ['(let [base {:a 1}] [(assoc base :b 2 :c 3 :d 4 :e "oops")])
           [(incm/mismatched-output-schema-msg
             {:expr 'abcde-maps-bad
              :arg '(let [base {:a 1}] [(assoc base :b 2 :c 3 :d 4 :e "oops")])}
             (ps/T [{:a s/Int :b s/Int :c s/Int :d s/Int :e s/Str}])
             (ps/T [{:a s/Int :b s/Int :c s/Int :d s/Int :e s/Int}]))]]
         (ps/result-errors (ps/check-fixture 'skeptic.test-examples.collections/abcde-maps-bad)))))

(deftest nested-call-mismatch-renders-field-paths
  (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/nested-map-input-failure
                                        {:remove-context true}))]
    (is (some? result))
    (is (= '(takes-nested-name {:user {:name :bad}})
           (:blame result)))
    (is (some #(str/includes? % "[:user :name]") (:errors result)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> result :cast-diagnostics first :path)))))

(deftest vector-call-mismatch-renders-index-paths
  (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/vector-input-failure
                                        {:remove-context true}))]
    (is (some? result))
    (is (= '(takes-int-pair (bad-int-pair-helper))
           (:blame result)))
    (is (some #(str/includes? % "[1]") (:errors result)))
    (is (= [{:kind :vector-index :index 1}]
           (-> result :cast-diagnostics first :path)))))

(deftest vector-literal-tuples-derive-homogeneous-views-at-check-boundary
  (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/vector-triple-to-homogeneous-success)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/vector-triple-to-fixed-success)))
  (let [pair-result (first (ps/check-fixture 'skeptic.test-examples.collections/vector-triple-to-pair-failure
                                             {:remove-context true}))
        quad-result (first (ps/check-fixture 'skeptic.test-examples.collections/vector-triple-to-quad-failure
                                             {:remove-context true}))]
    (is (some? pair-result))
    (is (= '(takes-int-pair [x y z]) (:blame pair-result)))
    (is (= :vector-arity-mismatch (-> pair-result :cast-diagnostics first :reason)))
    (is (some? quad-result))
    (is (= '(takes-int-quad [x y z]) (:blame quad-result)))
    (is (= :vector-arity-mismatch (-> quad-result :cast-diagnostics first :reason)))))
