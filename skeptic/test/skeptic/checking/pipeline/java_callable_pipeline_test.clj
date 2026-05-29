(ns skeptic.checking.pipeline.java-callable-pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.checking.pipeline.support :as ps]))

(defn- no-errors?
  [results]
  (every? (fn [r]
            (and (not= :exception (:report-kind r))
                 (empty? (:errors r))))
          results))

(defn- result-rules
  [results]
  (keep :rule results))

(defn- check-success
  [sym]
  (ps/check-fixture sym {:keep-empty true}))

(defn- has-rule?
  [sym rule]
  (some #{rule} (result-rules (ps/check-fixture sym))))

(clojure.test/use-fixtures :once ps/with-worker)
(deftest runnable-fixtures
  (testing "arity-0 fn satisfies Runnable"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/runnable-arity-0-success))))
  (testing "arity-2 fn fails Runnable arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/runnable-arity-2-failure
                   :java-callable-arity))))

(deftest callable-fixtures
  (testing "arity-0 fn satisfies Callable"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/callable-arity-0-success))))
  (testing "arity-1 fn fails Callable arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/callable-arity-1-failure
                   :java-callable-arity))))

(deftest comparator-fixtures
  (testing "arity-2 Int-returning fn satisfies Comparator"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/comparator-arity-2-int-success))))
  (testing "arity-1 fn fails Comparator arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/comparator-arity-1-failure
                   :java-callable-arity)))
  (testing "arity-2 String-returning fn fails Comparator return"
    (is (has-rule? 'skeptic.test-examples.java-callable/comparator-return-str-failure
                   :java-callable-return))))

(deftest function-fixtures
  (testing "arity-1 fn satisfies Function"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/function-arity-1-success))))
  (testing "arity-0 fn fails Function arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/function-arity-0-failure
                   :java-callable-arity))))

(deftest supplier-fixtures
  (testing "arity-0 fn satisfies Supplier"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/supplier-arity-0-success))))
  (testing "arity-1 fn fails Supplier arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/supplier-arity-1-failure
                   :java-callable-arity))))

(deftest consumer-fixtures
  (testing "arity-1 fn satisfies Consumer"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/consumer-arity-1-success))))
  (testing "arity-0 fn fails Consumer arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/consumer-arity-0-failure
                   :java-callable-arity))))

(deftest predicate-fixtures
  (testing "arity-1 Bool-returning fn satisfies Predicate"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/predicate-arity-1-bool-success))))
  (testing "arity-0 fn fails Predicate arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/predicate-arity-0-failure
                   :java-callable-arity)))
  (testing "arity-1 Int-returning fn fails Predicate return"
    (is (has-rule? 'skeptic.test-examples.java-callable/predicate-return-int-failure
                   :java-callable-return))))

(deftest bifunction-fixtures
  (testing "arity-2 fn satisfies BiFunction"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/bifunction-arity-2-success))))
  (testing "arity-1 fn fails BiFunction arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/bifunction-arity-1-failure
                   :java-callable-arity))))

(deftest bipredicate-fixtures
  (testing "arity-2 Bool-returning fn satisfies BiPredicate"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/bipredicate-arity-2-bool-success))))
  (testing "arity-1 fn fails BiPredicate arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/bipredicate-arity-1-failure
                   :java-callable-arity)))
  (testing "arity-2 Int-returning fn fails BiPredicate return"
    (is (has-rule? 'skeptic.test-examples.java-callable/bipredicate-return-int-failure
                   :java-callable-return))))

(deftest biconsumer-fixtures
  (testing "arity-2 fn satisfies BiConsumer"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/biconsumer-arity-2-success))))
  (testing "arity-1 fn fails BiConsumer arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/biconsumer-arity-1-failure
                   :java-callable-arity))))

(deftest unaryop-fixtures
  (testing "arity-1 fn satisfies UnaryOperator"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/unaryop-arity-1-success))))
  (testing "arity-2 fn fails UnaryOperator arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/unaryop-arity-2-failure
                   :java-callable-arity))))

(deftest binaryop-fixtures
  (testing "arity-2 fn satisfies BinaryOperator"
    (is (no-errors? (check-success
                     'skeptic.test-examples.java-callable/binaryop-arity-2-success))))
  (testing "arity-0 fn fails BinaryOperator arity"
    (is (has-rule? 'skeptic.test-examples.java-callable/binaryop-arity-0-failure
                   :java-callable-arity))))

(deftest non-table-class-unaffected
  (testing "java.util.HashMap target does not invoke the new rule"
    (let [results (ps/check-fixture
                   'skeptic.test-examples.java-callable/hashmap-fn-fixture
                   {:keep-empty true})
          rules   (result-rules results)]
      (is (not-any? #{:java-callable-arity :java-callable-return} rules)))))
