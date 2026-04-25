(ns skeptic.checking.pipeline.control-flow-test
  (:require [clojure.test :refer [are deftest is]]
            [schema.core :as s]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]))

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

(deftest sum-type-exhaustive-branches
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.control-flow/cond-boolean-exhaustive-output-success
    'skeptic.test-examples.control-flow/cond-boolean-pair-exhaustive-output-success
    'skeptic.test-examples.control-flow/cond-boolean-triple-exhaustive-output-success
    'skeptic.test-examples.control-flow/cond-get-union-predicate-exhaustive-output-success
    'skeptic.test-examples.control-flow/cond-enum-equality-exhaustive-output-success
    'skeptic.test-examples.control-flow/case-enum-exhaustive-output-success
    'skeptic.test-examples.control-flow/condp-enum-equality-exhaustive-output-success))

(deftest failing-functions
  (are [sym errors] (= (set (partition 2 errors))
                       (ps/result-pairs (ps/check-fixture sym)))
    'skeptic.test-examples.control-flow/sample-nil-local-arg-fn
    ['(int-add x y)
     [(incm/mismatched-schema-msg {:expr '(int-add x y) :arg 'y}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.control-flow/sample-bad-local-provenance-fn
    ['(int-add 1 nil)
     [(incm/mismatched-schema-msg {:expr '(int-add 1 nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.control-flow/sample-multi-line-body
    ['(int-add nil x)
     [(incm/mismatched-schema-msg {:expr '(int-add nil x) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.control-flow/sample-multi-line-let-body
    ['(int-add 1 (f x))
     [(incm/mismatched-schema-msg {:expr '(int-add 1 (f x)) :arg '(f x)}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]
     '(int-add 2 3 4 nil)
     [(incm/mismatched-schema-msg {:expr '(int-add 2 3 4 nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]
     '(int-add nil x)
     [(incm/mismatched-schema-msg {:expr '(int-add nil x) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]
     '(int-add 2 nil)
     [(incm/mismatched-schema-msg {:expr '(int-add 2 nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]
     '(int-add w 1 x y z)
     [(incm/mismatched-schema-msg {:expr '(int-add w 1 x y z) :arg 'w}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.control-flow/loop-recur-type-mismatch
    ['(recur "not-int")
     [(incm/mismatched-ground-type-msg {:expr '(recur "not-int") :arg "not-int"}
                                       (ps/T s/Str)
                                       (ps/T s/Int))]]

    'skeptic.test-examples.control-flow/sample-let-mismatched-types
    ['(int-add x s)
     [(incm/mismatched-ground-type-msg {:expr '(int-add x s) :arg 's}
                                       (ps/T s/Str)
                                       (ps/T s/Int))]]))

(deftest call-mismatch-reports-affected-input-and-location
  (let [results (ps/check-fixture-ns 'skeptic.test-examples.control-flow
                                     {:remove-context true})
        result (some #(when (= '(int-add x y) (:blame %))
                        %)
                     results)]
    (is (= ['y] (:focuses result)))
    (is (= ["y"] (:focus-sources result)))
    (is (= "(int-add x y)" (:source-expression result)))
    (is (= {:file (ps/fixture-path-for-ns 'skeptic.test-examples.control-flow)
            :line 13
            :column 5}
           (select-keys (:location result) [:file :line :column])))
    (is (= 'skeptic.test-examples.control-flow/sample-nil-local-arg-fn
           (:enclosing-form result)))))
