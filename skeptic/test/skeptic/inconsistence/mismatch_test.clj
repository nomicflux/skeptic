(ns skeptic.inconsistence.mismatch-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.inconsistence.mismatch :as sut]))

(def ^:private ui-internal-markers
  [":skeptic.analysis.types/"
   "placeholder-type"
   "group-type"
   ":ref "
   "source union branch"
   "target union branch"
   "source intersection branch"
   "target intersection branch"])

(defn- assert-no-ui-internals
  [text]
  (doseq [marker ui-internal-markers]
    (is (not (str/includes? (str text) marker)))))

(deftest mismatched-output-schema-msg-public-names-test
  (let [placeholder (at/->PlaceholderT 'clj-threals.threals/Threal)
        message (sut/mismatched-output-schema-msg
                 {:expr 'for-birthday
                  :arg '[g r b]}
                 (at/->VectorT [(at/->SetT #{(at/->VectorT [placeholder placeholder placeholder]
                                                      false)}
                                            false)]
                               true)
                 [s/Any])]
    (is (str/includes? message "Threal"))
    (assert-no-ui-internals message)))

(deftest unknown-output-type-test
  (is (sut/unknown-output-type? (ab/schema->type s/Any)))
  (is (sut/unknown-output-type? (ab/schema->type (s/maybe s/Any))))
  (is (sut/unknown-output-type? (ab/schema->type (sb/placeholder-schema [:output 'example/f]))))
  (is (not (sut/unknown-output-type? (ab/schema->type s/Int)))))

(deftest mismatched-messages-shape-test
  (let [ctx {:expr '(f 1) :arg 1}]
    (is (str/includes? (sut/mismatched-nullable-msg ctx nil nil) "nullable"))
    (is (str/includes? (sut/mismatched-ground-type-msg ctx s/Str s/Int) "mismatched type"))
    (is (str/includes? (sut/mismatched-schema-msg ctx s/Str s/Int) "expected type"))))
