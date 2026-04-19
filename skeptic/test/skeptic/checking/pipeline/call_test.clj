(ns skeptic.checking.pipeline.call-test
  (:require [clojure.test :refer [are deftest is]]
            [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.test-examples.basics :as basics]))

(deftest annotated-input-ground-type-mismatch
  (are [sym errors] (= (set (partition 2 errors))
                       (ps/result-pairs (ps/check-fixture sym)))
    'skeptic.test-examples.basics/sample-bad-annotation-fn
    ['(int-add not-an-int 2)
     [(incm/mismatched-ground-type-msg {:expr '(int-add not-an-int 2)
                                        :arg 'not-an-int}
                                       (ps/T s/Str)
                                       (ps/T s/Int))]]))

(deftest failing-functions
  (are [sym errors] (= (set (partition 2 errors))
                       (ps/result-pairs (ps/check-fixture sym)))
    'skeptic.test-examples.basics/sample-direct-nil-arg-fn
    ['(int-add nil x)
     [(incm/mismatched-schema-msg {:expr '(int-add nil x) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

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

    'skeptic.test-examples.basics/sample-mismatched-types
    ['(int-add x "hi")
     [(incm/mismatched-ground-type-msg {:expr '(int-add x "hi") :arg "hi"}
                                       (ps/T s/Str)
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
                                       (ps/T s/Int))]]

    'skeptic.test-examples.resolution/sample-let-fn-bad1-fn
    ['(int-add y nil)
     [(incm/mismatched-schema-msg {:expr '(int-add y nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.basics/sample-multi-arity-fn
    ['(int-add x y z nil)
     [(incm/mismatched-schema-msg {:expr '(int-add x y z nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]
     '(int-add x y nil)
     [(incm/mismatched-schema-msg {:expr '(int-add x y nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]
     '(int-add x nil)
     [(incm/mismatched-schema-msg {:expr '(int-add x nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.fixture-flags/sample-metadata-fn
    ['(int-add x nil)
     [(incm/mismatched-schema-msg {:expr '(int-add x nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.fixture-flags/sample-doc-fn
    ['(int-add x nil)
     [(incm/mismatched-schema-msg {:expr '(int-add x nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.fixture-flags/sample-doc-and-metadata-fn
    ['(int-add x nil)
     [(incm/mismatched-schema-msg {:expr '(int-add x nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.fixture-flags/sample-fn-once
    ['(int-add y nil)
     [(incm/mismatched-schema-msg {:expr '(int-add y nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.basics/sample-bad-parametric-fn
    ['(. clojure.lang.Numbers (add 1 (g f)))
     [(incm/mismatched-schema-msg {:expr '(. clojure.lang.Numbers (add 1 (g f)))
                                   :arg '(g f)}
                                  (ps/T (s/eq nil))
                                  at/NumericDyn)]]))

(deftest fn-chain-type-errors
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/fn-chain-success)))
  (is (= ['(g (f x))
           [(incm/mismatched-ground-type-msg
             {:expr '(g (f x)) :arg '(f x)}
             (ps/T s/Str)
             (ps/T s/Int))]]
         (ps/result-errors (ps/check-fixture 'skeptic.test-examples.basics/fn-chain-failure)))))

(deftest annotated-wrapper-regression
  (is (= ['(int-add nil x)
           [(incm/mismatched-schema-msg {:expr '(int-add nil x) :arg nil}
                                        (ps/T (s/eq nil))
                                        (ps/T s/Int))]]
         (ps/result-errors
          (ps/check-fixture 'skeptic.test-examples.basics/sample-annotated-bad-fn)))))

(deftest checking-annotated-wrapper-regression
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/sample-named-input-fn)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/sample-named-output-fn)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/sample-constrained-output-fn)))
  (is (= ['x
           [(incm/mismatched-output-schema-msg
             {:expr 'sample-bad-constrained-output-fn
              :arg 'x}
             (ps/T s/Str)
             (ps/T basics/PosInt))]]
         (ps/result-errors
          (ps/check-fixture 'skeptic.test-examples.basics/sample-bad-constrained-output-fn)))))

(deftest numeric-inc-checks
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/inc-int-success)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/inc-num-success)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/inc-double-success))))
