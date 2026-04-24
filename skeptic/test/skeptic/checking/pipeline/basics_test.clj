(ns skeptic.checking.pipeline.basics-test
  (:require [clojure.test :refer [are deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.test-examples.basics :as basics]
            [skeptic.typed-decls :as typed-decls]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(defn- named-schema-type
  [sym schema]
  (ab/schema->type (prov/make-provenance :schema sym 'skeptic.test nil) schema))

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

    'skeptic.test-examples.basics/sample-mismatched-types
    ['(int-add x "hi")
     [(incm/mismatched-ground-type-msg {:expr '(int-add x "hi") :arg "hi"}
                                       (ps/T s/Str)
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

    'skeptic.test-examples.basics/sample-bad-parametric-fn
    ['(. clojure.lang.Numbers (add 1 (g f)))
     [(incm/mismatched-schema-msg {:expr '(. clojure.lang.Numbers (add 1 (g f)))
                                   :arg '(g f)}
                                  (ps/T (s/eq nil))
                                  (at/NumericDyn tp))]]

    'skeptic.test-examples.basics/sample-str-fn
    ['(int-add 1 (str x))
     [(incm/mismatched-ground-type-msg {:expr '(int-add 1 (str x)) :arg '(str x)}
                                       (ps/T s/Str)
                                       (ps/T s/Int))]
     '(int-add 1 y)
     [(incm/mismatched-ground-type-msg {:expr '(int-add 1 y) :arg 'y}
                                       (ps/T s/Str)
                                       (ps/T s/Int))]]))

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
             (named-schema-type 'skeptic.test-examples.basics/PosInt basics/PosInt))]]
         (ps/result-errors
          (ps/check-fixture 'skeptic.test-examples.basics/sample-bad-constrained-output-fn)))))

(deftest numeric-inc-checks
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/inc-int-success)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/inc-num-success)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/inc-double-success))))

(deftest regex-return-declaration-and-checking
  (let [{:keys [dict errors]} (typed-decls/typed-ns-results {} 'skeptic.test-examples.basics)
        entries dict
        declaration-error (some #(when (= 'skeptic.test-examples.basics/regex-return-caller
                                          (:blame %))
                                   %)
                                errors)
        namespace-results (ps/check-fixture-namespace 'skeptic.test-examples.basics
                                                      {:remove-context true})
        namespace-declaration-error (some #(when (and (= :declaration (:phase %))
                                                      (= 'skeptic.test-examples.basics/regex-return-caller
                                                         (:blame %)))
                                             %)
                                          namespace-results)]
    (is (contains? entries 'skeptic.test-examples.basics/regex-return-caller))
    (is (nil? declaration-error)
        (str "expected regex-return-caller to admit cleanly; got " (pr-str declaration-error)))
    (is (nil? namespace-declaration-error)
        (str "expected no declaration exception for regex-return-caller; got "
             (pr-str namespace-declaration-error)))
    (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/regex-return-caller)))))

(deftest multi-arity-per-arity-output-checking
  (is (= ['y
          [(incm/mismatched-output-schema-msg
            {:expr 'multi-arity-wrong-output
             :arg 'y}
            (ps/T s/Str)
            (ps/T s/Int))]]
         (ps/result-errors
          (ps/check-fixture 'skeptic.test-examples.basics/multi-arity-wrong-output)))))

(deftest check-ns-uses-raw-forms
  (let [results (ps/check-fixture-ns 'skeptic.test-examples.basics
                                     {:remove-context true})]
    (is (seq results))
    (is (some #(= '(int-add x "hi") (:blame %)) results))
    (is (not-any? #(and (seq? (:blame %))
                        (= "schema.core" (namespace (first (:blame %)))))
                  results))))
