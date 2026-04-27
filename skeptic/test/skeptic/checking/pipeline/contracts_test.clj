(ns skeptic.checking.pipeline.contracts-test
  (:require [clojure.test :refer [are deftest is]]
            [skeptic.checking.pipeline.support :as ps]))

(deftest checking-conditional-input-contracts
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/conditional-input-int-success
    'skeptic.test-examples.contracts/conditional-input-str-success
    'skeptic.test-examples.contracts/cond-pre-input-int-success
    'skeptic.test-examples.contracts/cond-pre-input-str-success
    'skeptic.test-examples.contracts/either-input-int-success
    'skeptic.test-examples.contracts/either-input-str-success
    'skeptic.test-examples.contracts/if-input-int-success
    'skeptic.test-examples.contracts/if-input-str-success
    'skeptic.test-examples.contracts/both-any-int-input-success)
  (are [sym blame] (ps/single-failure? sym blame)
    'skeptic.test-examples.contracts/conditional-input-keyword-failure '(takes-conditional-branch :bad)
    'skeptic.test-examples.contracts/cond-pre-input-keyword-failure '(takes-cond-pre-branch :bad)
    'skeptic.test-examples.contracts/either-input-keyword-failure '(takes-either-branch :bad)
    'skeptic.test-examples.contracts/if-input-keyword-failure '(takes-if-branch :bad)
    'skeptic.test-examples.contracts/both-any-int-input-str-failure '(takes-both-any-int "hi")
    'skeptic.test-examples.contracts/both-int-str-input-int-failure '(takes-both-int-str 1)
    'skeptic.test-examples.contracts/both-int-str-input-str-failure '(takes-both-int-str "hi")))

(deftest output-keyword-failure-includes-source-location
  (let [results (ps/check-fixture 'skeptic.test-examples.contracts/conditional-output-keyword-failure)
        result (first results)]
    (is (= 1 (count results)))
    (is (= :bad (:blame result)))
    (is (some? (-> result :location :line)))
    (is (= (ps/fixture-path 'skeptic.test-examples.contracts/conditional-output-keyword-failure)
           (str (-> result :location :file))))))

(deftest checking-conditional-output-contracts
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/conditional-output-int-success
    'skeptic.test-examples.contracts/conditional-output-str-success
    'skeptic.test-examples.contracts/cond-pre-output-int-success
    'skeptic.test-examples.contracts/cond-pre-output-str-success
    'skeptic.test-examples.contracts/either-output-int-success
    'skeptic.test-examples.contracts/either-output-str-success
    'skeptic.test-examples.contracts/if-output-int-success
    'skeptic.test-examples.contracts/if-output-str-success
    'skeptic.test-examples.contracts/both-any-int-output-success)
  (are [sym blame] (ps/single-failure? sym blame)
    'skeptic.test-examples.contracts/conditional-output-keyword-failure :bad
    'skeptic.test-examples.contracts/cond-pre-output-keyword-failure :bad
    'skeptic.test-examples.contracts/either-output-keyword-failure :bad
    'skeptic.test-examples.contracts/if-output-keyword-failure :bad
    'skeptic.test-examples.contracts/both-int-str-output-int-failure 1
    'skeptic.test-examples.contracts/both-int-str-output-str-failure "hi"))

(deftest conditional-contract-contains-key-refinement
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/conditional-map-if-a-success
    'skeptic.test-examples.contracts/conditional-map-if-b-success
    'skeptic.test-examples.contracts/conditional-map-alias-success
    'skeptic.test-examples.contracts/conditional-map-cond-thread-success)
  (are [sym blame] (ps/single-failure? sym blame)
    'skeptic.test-examples.contracts/conditional-map-if-a-bad-branch '(takes-has-b x)
    'skeptic.test-examples.contracts/conditional-map-if-b-bad-branch '(takes-has-a x)
    'skeptic.test-examples.contracts/optional-map-contains-does-not-refine '(takes-has-a x)))

(deftest conditional-contract-cond-thread-output-construction
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/mk-ab-unannotated-int-success
    'skeptic.test-examples.contracts/mk-ab-unannotated-str-success
    'skeptic.test-examples.contracts/mk-ab-annotated-int-return-success
    'skeptic.test-examples.contracts/mk-ab-annotated-str-return-success))

(deftest nested-conditional-contract-cond-thread
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/mk-takes-a-or-b-success-int
    'skeptic.test-examples.contracts/mk-takes-a-or-b-success-str
    'skeptic.test-examples.contracts/mk-takes-a-or-b-success-nil
    'skeptic.test-examples.contracts/has-a-or-b-identity-success
    'skeptic.test-examples.contracts/nested-has-a-or-b-identity-success
    'skeptic.test-examples.contracts/has-a-or-b-conditional-success-a
    'skeptic.test-examples.contracts/has-a-or-b-conditional-success-b
    'skeptic.test-examples.contracts/nested-has-a-or-b-conditional-success-a
    'skeptic.test-examples.contracts/nested-has-a-or-b-conditional-success-b)
  (are [sym blame] (ps/single-failure? sym blame)
    'skeptic.test-examples.contracts/mk-takes-a-or-b-failure-outer '(takes-a-or-b {:a :nope})
    'skeptic.test-examples.contracts/mk-takes-a-or-b-failure-inner '(takes-a-or-b {:c {:d :nope}})
    'skeptic.test-examples.contracts/mk-takes-a-or-b-failure-inner-inner '(takes-a-or-b {:c {:a :nope}})))

(deftest handles-ab-case-routing
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/handles-a
    'skeptic.test-examples.contracts/handles-b
    'skeptic.test-examples.contracts/handles-ab
    'skeptic.test-examples.contracts/handles-ab-destructured-route))

(deftest type-narrowing-examples
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/conditional-dispatch-success
    'skeptic.test-examples.contracts/cond-branch-pick-success
    'skeptic.test-examples.contracts/if-nullable-guard-success
    'skeptic.test-examples.contracts/cond->-guard-success))

(deftest higher-order-function-contract-rejects-unknown-callback
  (let [results (ps/check-fixture 'skeptic.test-examples.contracts/outer-fn)
        result (first results)]
    (is (= 1 (count results)))
    (is (= '(outer-fn (fn [] nil)) (:blame result)))
    (is (seq (:errors result)))
    (is (not (re-find #"Unknown" (pr-str results)))
        (pr-str results))))

(deftest destructured-local-narrowing-branches
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/if-blank-guard-optional-keys-branches-success
    'skeptic.test-examples.contracts/if-some-guard-destructured-success))

(deftest or-default-fills-missing-optional-key
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/or-default-optional-key-success)
  (let [results (ps/check-fixture
                 'skeptic.test-examples.contracts/or-default-optional-key-keyword-default-failure)
        result (first results)
        err (first (:errors result))]
    (is (= 1 (count results)))
    (is (= '(or-default-int-helper x) (:blame result)))
    (is (re-find #"(?s)x.*in.*\(or-default-int-helper x\).*has inferred type incompatible with the expected type:.*\(union (Int Keyword|Keyword Int)\).*The expected type corresponds to:.*Int"
                 (ps/strip-ansi err)))))

(deftest nested-conditional-destructured-discriminator-narrowing
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/nested-conditional-repro))

(deftest nested-conditional-classifier-descriptor-narrowing
  (are [sym] (= [] (ps/check-fixture sym))
    'skeptic.test-examples.contracts/nested-conditional-repro-top))
