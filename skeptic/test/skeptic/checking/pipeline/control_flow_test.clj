(ns skeptic.checking.pipeline.control-flow-test
  (:require [clojure.test :refer [are deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest loop-return-matches-declared-vector-and-map-schemas
  (are [sym] (empty? (ps/result-errors (ps/check-fixture sym)))
    'skeptic.test-examples.control-flow/loop-returns-int-vec-literal
    'skeptic.test-examples.control-flow/loop-returns-nested-schema-map
    'skeptic.test-examples.control-flow/loop-recur-accumulates-int-vec
    'skeptic.test-examples.control-flow/loop-recur-nested-schema-map))

(deftest for-declared-int-seq-output-must-type-check
  (let [results (ps/check-fixture 'skeptic.test-examples.control-flow/for-declared-int-seq-output)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest for-declared-str-seq-output-fails-when-body-is-int-seq
  (let [results (ps/check-fixture 'skeptic.test-examples.control-flow/for-declared-str-seq-body-int-seq)]
    (is (seq (ps/result-errors results))
        (str "expected output mismatch errors; got none: " (pr-str results)))))

(deftest for-even-str-odd-int-declared-int-seq-fails
  (let [results (ps/check-fixture 'skeptic.test-examples.control-flow/for-even-str-odd-int-declared-int-seq)]
    (is (seq (ps/result-errors results))
        (str "expected int-seq vs mixed str/int body errors; got none: " (pr-str results)))))

(deftest for-even-str-odd-int-declared-str-seq-fails
  (let [results (ps/check-fixture 'skeptic.test-examples.control-flow/for-even-str-odd-int-declared-str-seq)]
    (is (seq (ps/result-errors results))
        (str "expected str-seq vs mixed str/int body errors; got none: " (pr-str results)))))

(deftest for-even-str-odd-int-declared-cond-pre-seq-succeeds
  (let [results (ps/check-fixture 'skeptic.test-examples.control-flow/for-even-str-odd-int-declared-cond-pre-seq)]
    (is (empty? (ps/result-errors results))
        (str "expected no checker errors; got: " (pr-str (ps/result-errors results))))))

(deftest deep-unary-check-completes-within-timeout
  (let [results (ps/run-with-timeout 3000
                                     #(ps/check-fixture
                                       'skeptic.test-examples.control-flow/deep-unary
                                       {:remove-context true}))]
    (is (not= ::ps/timeout results)
        "Timed out checking skeptic.test-examples.control-flow/deep-unary")
    (is (= [] results))))

(deftest cond-three-branch-join-output
  (is (= [] (ps/check-fixture 'skeptic.test-examples.control-flow/cond-three-branch-join))))

(deftest when-not-blank-maybe-str
  (is (= [] (ps/check-fixture 'skeptic.test-examples.nullability/when-not-blank-maybe-str-success))))
