(ns skeptic.inconsistence-test
  (:require [skeptic.inconsistence :as sut]
            [clojure.test :refer [is are deftest]]
            [schema.core :as s]
            [skeptic.analysis.schema :as as]))

;; Used to generate useful messages, just need a placeholder for these tests
;; to check whether they flag an error or not
(def sample-ctx
  {:expr '(f x 2)
   :arg 'x})

(defn schema-or-value
  [s v]
  (as/schema-join [(s/eq v) s]))

(deftest mismatched-maybe-test
  (is (nil? (sut/mismatched-maybe sample-ctx (s/maybe s/Int) (s/maybe s/Int))))
  (is (nil? (sut/mismatched-maybe sample-ctx (s/maybe s/Int) s/Int)))
  (is (nil? (sut/mismatched-maybe sample-ctx s/Int s/Int)))
  (is (nil? (sut/mismatched-maybe sample-ctx (s/maybe s/Any) s/Any)))
  (is (nil? (sut/mismatched-maybe sample-ctx s/Any (s/maybe s/Any))))

  (is (not (nil? (sut/mismatched-maybe sample-ctx s/Int (s/maybe s/Int))))))

(deftest mismatched-ground-types-test
  (is (nil? (sut/mismatched-ground-types sample-ctx (s/maybe s/Int) s/Str)))
  (is (nil? (sut/mismatched-ground-types sample-ctx s/Int (s/maybe s/Str))))
  (is (nil? (sut/mismatched-ground-types sample-ctx s/Any s/Int)))
  (is (nil? (sut/mismatched-ground-types sample-ctx s/Int s/Any)))

  (is (not (nil? (sut/mismatched-ground-types sample-ctx s/Int s/Str))))
  (is (not (nil? (sut/mismatched-ground-types sample-ctx s/Str s/Int))))
  (is (not (nil? (sut/mismatched-ground-types sample-ctx s/Bool s/Symbol))))
  (is (not (nil? (sut/mismatched-ground-types sample-ctx s/Bool s/Int)))))

(deftest apply-base-rules-test
  (is (empty? (sut/apply-base-rules sample-ctx s/Int s/Any)))
  (is (seq (sut/apply-base-rules sample-ctx s/Int s/Str)))
  (is (empty? (sut/apply-base-rules sample-ctx (s/maybe s/Int) s/Int)))
  (is (seq (sut/apply-base-rules sample-ctx s/Int (s/maybe s/Int)))))

(deftest mismatched-key-schemas
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword (s/maybe s/Int)} {(schema-or-value s/Keyword :a) (schema-or-value (s/maybe s/Int) nil)})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword (s/maybe s/Int)} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx
                                           {s/Keyword s/Int s/Str s/Str}
                                           {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                            (schema-or-value s/Str "b") (schema-or-value s/Str "hello")})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {:a s/Int})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword (s/maybe s/Int)} {:a s/Int})))

  ;(is (seq (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {:a s/Str})))
  (is (seq (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value (s/maybe s/Int) nil)})))
  (is (seq (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Str "hello")})))
  (is (seq (sut/apply-mismatches-by-key sample-ctx
                                        {s/Keyword s/Int s/Str s/Str}
                                        {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                         (schema-or-value s/Str "b") (schema-or-value s/Int 1)})))
  (is (seq (sut/apply-mismatches-by-key sample-ctx
                                        {s/Keyword s/Int s/Str s/Str}
                                        {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                         (schema-or-value s/Str "b") (schema-or-value s/Int 2)})))
  )

(deftest check-keys-test
  (is (nil? (sut/check-keys sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (nil? (sut/check-keys sample-ctx {:a s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (nil? (sut/check-keys sample-ctx {(s/optional-key s/Keyword) s/Int} {})))
  (is (nil? (sut/check-keys sample-ctx {(s/optional-key s/Keyword) s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (nil? (sut/check-keys sample-ctx {(s/optional-key :a) s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (nil? (sut/check-keys sample-ctx {s/Keyword s/Int} {:a s/Int})))
  (is (nil? (sut/check-keys sample-ctx {:a s/Int} {:a s/Int})))
  (is (nil? (sut/check-keys sample-ctx {(s/optional-key :a) s/Int} {:a s/Int})))
  (is (nil? (sut/check-keys sample-ctx {(s/optional-key :a) s/Int} {(s/optional-key :a) s/Int})))

  (is (not (nil? (sut/check-keys sample-ctx {s/Str s/Int} {:a s/Int}))))
  (is (not (nil? (sut/check-keys sample-ctx {:a s/Int} {(s/optional-key :a) s/Int}))))
  (is (not (nil? (sut/check-keys sample-ctx {} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)}))))
  (is (not (nil? (sut/check-keys sample-ctx {s/Str s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)}))))
  (is (not (nil? (sut/check-keys sample-ctx {:b s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)}))))
  )

(deftest mismatched-maps-test
  (is (empty? (sut/mismatched-maps sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx {s/Keyword (s/maybe s/Int)} {(schema-or-value s/Keyword :a) (schema-or-value (s/maybe s/Int) nil)})))
  (is (empty? (sut/mismatched-maps sample-ctx {s/Keyword (s/maybe s/Int)} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx
                                           {s/Keyword s/Int s/Str s/Str}
                                           {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                            (schema-or-value s/Str "b") (schema-or-value s/Str "hello")})))

  (is (seq (sut/mismatched-maps sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value (s/maybe s/Int) nil)})))
  (is (seq (sut/mismatched-maps sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Str "hello")})))
  (is (seq (sut/mismatched-maps sample-ctx
                                        {s/Keyword s/Int s/Str s/Str}
                                        {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                         (schema-or-value s/Str "b") (schema-or-value s/Int 1)})))
  (is (seq (sut/mismatched-maps sample-ctx
                                        {s/Keyword s/Int s/Str s/Str}
                                        {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                         (schema-or-value s/Str "b") (schema-or-value s/Int 2)})))

  (is (empty? (sut/mismatched-maps sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx {:a s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx  {(s/optional-key s/Keyword) s/Int} {})))
  (is (empty? (sut/mismatched-maps sample-ctx {(s/optional-key s/Keyword) s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx {(s/optional-key :a) s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))

  (is (seq (sut/mismatched-maps sample-ctx {} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (seq (sut/mismatched-maps sample-ctx {s/Str s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (seq (sut/mismatched-maps sample-ctx {:b s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  )
