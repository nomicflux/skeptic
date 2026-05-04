(ns skeptic.inconsistence.mismatch-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.provenance :as prov]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.inconsistence.mismatch :as sut]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

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
  (let [placeholder (at/->PlaceholderT tp 'clj-threals.threals/Threal)
        message (sut/mismatched-output-schema-msg
                 {:expr 'for-birthday
                  :arg '[g r b]}
                 (at/->VectorT tp []
                               (at/->SetT tp #{(at/->VectorT tp [placeholder placeholder placeholder] nil)}
                                          false))
                 [s/Any])]
    (is (str/includes? message "Threal"))
    (assert-no-ui-internals message)))

(deftest unknown-output-type-test
  (is (sut/unknown-output-type? (ab/schema->type tp s/Any)))
  (is (not (sut/unknown-output-type? (ab/schema->type tp (s/maybe s/Any)))))
  (is (not (sut/unknown-output-type? (ab/schema->type tp (sb/placeholder-schema [:output 'example/f])))))
  (is (not (sut/unknown-output-type? (ab/schema->type tp s/Int)))))

(deftest mismatched-messages-shape-test
  (let [ctx {:expr '(f 1) :arg 1}]
    (is (str/includes? (sut/mismatched-nullable-msg ctx nil nil) "nullable"))
    (is (str/includes? (sut/mismatched-ground-type-msg ctx s/Str s/Int) "mismatched type"))
    (is (str/includes? (sut/mismatched-schema-msg ctx s/Str s/Int) "expected type"))))
