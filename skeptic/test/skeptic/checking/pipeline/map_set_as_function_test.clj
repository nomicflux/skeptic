(ns skeptic.checking.pipeline.map-set-as-function-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.checking.pipeline.support :as ps]))

(clojure.test/use-fixtures :once ps/with-worker)

(deftest map-as-function-cases
  (testing "map literal fits a matching function type: clean"
    (is (= [] (ps/check-fixture 'skeptic.test-examples.map-set-as-function/map-as-function-success))))
  (testing "keyword keys fail a Str-input function type: domain mismatch"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.map-set-as-function/map-as-function-wrong-input-failure
                                          {:remove-context true}))]
      (is (some? result))
      (is (= '(call-str-to-int {:a 1 :b 2} "a") (:blame result)))
      (is (= :function-domain (-> result :cast-diagnostics first :path first :kind)))))
  (testing "Int values fail a Str-output function type: range mismatch"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.map-set-as-function/map-as-function-wrong-output-failure
                                          {:remove-context true}))]
      (is (some? result))
      (is (= '(call-kw-to-str {:a 1 :b 2} :a) (:blame result)))
      (is (= :function-range (-> result :cast-diagnostics first :path first :kind))))))

(deftest set-as-function-cases
  (testing "set literal fits (=> (maybe Keyword) Keyword): clean"
    (is (= [] (ps/check-fixture 'skeptic.test-examples.map-set-as-function/set-as-function-success))))
  (testing "keyword members fail a Str-input function type: domain mismatch"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.map-set-as-function/set-as-function-wrong-input-failure
                                          {:remove-context true}))]
      (is (some? result))
      (is (= '(call-str-to-maybe-kw #{:b :a} "a") (:blame result)))
      (is (= :function-domain (-> result :cast-diagnostics first :path first :kind)))))
  (testing "keyword members fail a (maybe Str)-output function type: range mismatch"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.map-set-as-function/set-as-function-wrong-output-failure
                                          {:remove-context true}))]
      (is (some? result))
      (is (= '(call-kw-to-maybe-str #{:b :a} :a) (:blame result)))
      (is (= :function-range (-> result :cast-diagnostics first :path first :kind)))))
  (testing "non-nullable output requirement fails: set lookup returns (maybe member)"
    (let [result (first (ps/check-fixture 'skeptic.test-examples.map-set-as-function/set-as-function-non-nullable-output-failure
                                          {:remove-context true}))]
      (is (some? result))
      (is (= '(call-kw-to-kw #{:b :a} :a) (:blame result)))
      (is (= :function-range (-> result :cast-diagnostics first :path first :kind))))))
