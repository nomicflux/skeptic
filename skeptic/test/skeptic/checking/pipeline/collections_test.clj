(ns skeptic.checking.pipeline.collections-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.output.text :as output-text]))

(deftest abcde-maps-output-type-errors
  (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/abcde-maps)))
  (is (= ['(let [base {:a 1}] [(assoc base :b 2 :c 3 :d 4 :e "oops")])
           [(incm/mismatched-output-schema-msg
             {:expr 'abcde-maps-bad
              :arg '(let [base {:a 1}] [(assoc base :b 2 :c 3 :d 4 :e "oops")])}
             (at/->VectorT ps/tp [(ps/T {:a s/Int :b s/Int :c s/Int :d s/Int :e s/Str})] nil)
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

(deftest one-prefixed-open-vector-schema-accepts-trailing-any
  (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/one-prefixed-open-success))))

(deftest open-prefix-plus-concrete-tail-cases
  (testing "extras typed by tail: clean"
    (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/open-tail-success-extras))))
  (testing "no extras: clean"
    (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/open-tail-success-no-extras))))
  (testing "prefix mismatch: ground-mismatch on first slot"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/open-tail-failure-prefix
                                          {:remove-context true}))
          diag (-> result :cast-diagnostics first)]
      (is (some? result))
      (is (= :leaf-mismatch (:reason diag)))
      (is (= 0 (-> diag :path first :index)))))
  (testing "tail-element mismatch: ground-mismatch on tail index"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/open-tail-failure-tail-elem
                                          {:remove-context true}))
          diag (-> result :cast-diagnostics first)]
      (is (some? result))
      (is (= :leaf-mismatch (:reason diag)))
      (is (>= (-> diag :path first :index) 2)))))

(deftest closed-prefix-cases
  (testing "exact arity: clean"
    (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/closed-prefix-success))))
  (testing "too long: arity mismatch"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/closed-prefix-too-long
                                          {:remove-context true}))]
      (is (some? result))
      (is (= :vector-arity-mismatch (-> result :cast-diagnostics first :reason))))))

(deftest cond-pre-tail-cases
  (testing "all tail elements satisfy cond-pre: clean"
    (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/cond-pre-tail-success))))
  (testing "tail element fails cond-pre: union mismatch"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/cond-pre-tail-failure
                                          {:remove-context true}))]
      (is (some? result))
      (is (= 1 (-> result :cast-diagnostics first :path first :index))))))

(deftest printer-path-renders-only-user-facing-data
  (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/nested-map-input-failure
                                        {:remove-context true}))
        summary (inrep/report-summary result)
        printed (str/join "\n"
                          (concat (map (fn [[label value]]
                                         (str label value))
                                       (output-text/report-fields summary))
                                  (:errors summary)))]
    (is (some? result))
    (is (str/includes? printed "[:user :name]"))
    (ps/assert-no-ui-internals printed)))

(deftest homogeneous-tail-element-mismatch-case
  (testing "non-Int element in [s/Int] slot: leaf-mismatch on its index"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/homogeneous-tail-element-failure
                                          {:remove-context true}))
          diag (-> result :cast-diagnostics first)]
      (is (some? result))
      (is (= :leaf-mismatch (:reason diag)))
      (is (= 1 (-> diag :path first :index))))))

(deftest empty-closed-vector-cases
  (testing "empty arg matches []: clean"
    (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/empty-closed-success))))
  (testing "non-empty arg into []: arity mismatch"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/empty-closed-too-long
                                          {:remove-context true}))]
      (is (some? result))
      (is (= :vector-arity-mismatch (-> result :cast-diagnostics first :reason))))))

(deftest maybe-tail-cases
  (testing "string tail elements satisfy (maybe Str): clean"
    (is (= [] (ps/check-fixture 'skeptic.test-examples.collections/maybe-tail-success-strs))))
  (testing "int tail element fails (maybe Str): mismatch on tail index"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/maybe-tail-failure-int
                                          {:remove-context true}))]
      (is (some? result))
      (is (= 1 (-> result :cast-diagnostics first :path first :index))))))
