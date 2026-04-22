(ns skeptic.checking.pipeline.fixture-flags-test
  (:require [clojure.test :refer [are deftest is]]
            [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.config :as config]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.typed-decls :as typed-decls]))

(deftest ignore-body-fixtures
  (is (empty? (ps/check-fixture 'skeptic.test-examples.fixture-flags/ignored-body-fn))
      "body of ignore-body fn should not produce mismatches")
  (is (seq (ps/check-fixture 'skeptic.test-examples.fixture-flags/caller-of-ignored))
      "caller check should still fire against the declared schema")
  (is (empty? (ps/check-fixture 'skeptic.test-examples.fixture-flags/good-caller-of-ignored))
      "correct callers should have no errors"))

(deftest opaque-fixtures
  (is (empty? (ps/check-fixture 'skeptic.test-examples.fixture-flags/opaque-fn))
      "opaque fn body must not produce mismatches")
  (is (empty? (ps/check-fixture 'skeptic.test-examples.fixture-flags/caller-of-opaque))
      "caller of opaque fn must see s/Any and produce no mismatches")
  (is (not (contains? (:dict (sut/namespace-dict {} 'skeptic.test-examples.fixture-flags))
                      'skeptic.test-examples.fixture-flags/opaque-fn))
      "opaque fn must be absent from dict so callers see Dyn"))

(deftest expression-type-override
  (is (empty? (ps/check-fixture 'skeptic.test-examples.fixture-flags/override-fn))
      ":skeptic/type override should pin (str-helper) to s/Int so int-add is happy")
  (is (seq (ps/check-fixture 'skeptic.test-examples.fixture-flags/override-wrong-fn))
      "a wrong :skeptic/type override (pin s/Int result to s/Str) must trigger a downstream mismatch"))

(deftest type-overrides-merge-into-typed-ns-results
  (let [overrides (config/compile-overrides {'some.ns/some-fn {:schema 's/Int}})
        opts {:skeptic/type-overrides overrides}
        result (typed-decls/typed-ns-results opts 'skeptic.test-examples.fixture-flags)]
    (is (at/type=? (ps/T s/Int)
                   (get-in result [:dict 'some.ns/some-fn]))
        "typed-ns-results must merge :skeptic/type-overrides into :dict")))

(deftest failing-functions
  (are [sym errors] (= (set (partition 2 errors))
                       (ps/result-pairs (ps/check-fixture sym)))
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
                                  (ps/T s/Int))]]))
