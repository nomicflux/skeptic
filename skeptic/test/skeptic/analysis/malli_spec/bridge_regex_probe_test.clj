(ns skeptic.analysis.malli-spec.bridge-regex-probe-test
  "Probe: capture exact m/form output for sequence/regex combinators so slice 9
  design choices rest on verified shapes rather than paraphrased docs.

  These tests assert specific m/form return shapes. If any assertion fails, the
  test failure surfaces the actual form, which is the data the slice needs."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]))

(defn- form
  [v]
  (m/form (m/schema v)))

(deftest star-form-roundtrips
  (is (= [:* :string] (form [:* :string]))))

(deftest plus-form-roundtrips
  (is (= [:+ :string] (form [:+ :string]))))

(deftest question-form-roundtrips
  (is (= [:? :string] (form [:? :string]))))

(deftest repeat-form-roundtrips
  (is (= [:repeat {:min 1 :max 3} :int] (form [:repeat {:min 1 :max 3} :int]))))

(deftest alt-form-roundtrips
  (is (= [:alt :int :string] (form [:alt :int :string]))))

(deftest catn-form-roundtrips
  (is (= [:catn [:s :string] [:n :int]] (form [:catn [:s :string] [:n :int]]))))

(deftest altn-form-roundtrips
  (is (= [:altn [:kw :keyword] [:s :string]] (form [:altn [:kw :keyword] [:s :string]]))))

(deftest cat-with-star-tail
  (is (= [:cat :int [:* :string]] (form [:cat :int [:* :string]]))))

(deftest cat-with-question
  (is (= [:cat :int [:? :string]] (form [:cat :int [:? :string]]))))

(deftest cat-with-alt-element
  (is (= [:cat :int [:alt :string :keyword]] (form [:cat :int [:alt :string :keyword]]))))

(deftest cat-with-repeat
  (is (= [:cat :int [:repeat {:min 1 :max 2} :string]]
         (form [:cat :int [:repeat {:min 1 :max 2} :string]]))))

(deftest fn-schema-with-cat-star
  (is (= [:=> [:cat :int [:* :string]] :int]
         (form [:=> [:cat :int [:* :string]] :int]))))

(deftest fn-schema-with-catn
  (is (= [:=> [:catn [:s :string] [:n :int]] :int]
         (form [:=> [:catn [:s :string] [:n :int]] :int]))))

(deftest fn-schema-with-cat-only-star
  (is (= [:=> [:cat [:* :int]] :int]
         (form [:=> [:cat [:* :int]] :int]))))

(deftest fn-schema-with-cat-only-question
  (is (= [:=> [:cat [:? :int]] :int]
         (form [:=> [:cat [:? :int]] :int]))))

(deftest fn-schema-with-cat-only-plus
  (is (= [:=> [:cat [:+ :int]] :int]
         (form [:=> [:cat [:+ :int]] :int]))))
