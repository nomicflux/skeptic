(ns skeptic.checking.pipeline.fixture-flags-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.config :as config]
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
  (is (= (ps/T s/Any)
         (:output-type (get ps/test-dict 'skeptic.test-examples.fixture-flags/opaque-fn)))
      "opaque fn dict entry must have s/Any output type"))

(deftest expression-type-override
  (is (empty? (ps/check-fixture 'skeptic.test-examples.fixture-flags/override-fn))
      ":skeptic/type override should pin (str-helper) to s/Int so int-add is happy")
  (is (seq (ps/check-fixture 'skeptic.test-examples.fixture-flags/override-wrong-fn))
      "a wrong :skeptic/type override (pin s/Int result to s/Str) must trigger a downstream mismatch"))

(deftest type-overrides-merge-into-typed-ns-results
  (let [overrides (config/compile-overrides {'some.ns/some-fn {:output 's/Int}})
        opts {:skeptic/type-overrides overrides}
        result (typed-decls/typed-ns-results opts 'skeptic.test-examples.fixture-flags)]
    (is (= (ps/T s/Int)
           (get-in result [:entries 'some.ns/some-fn :output-type]))
        "typed-ns-results must merge :skeptic/type-overrides into :entries")))
