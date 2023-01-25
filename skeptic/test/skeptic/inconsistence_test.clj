(ns skeptic.inconsistence-test
  (:require [skeptic.inconsistence :as sut]
            [clojure.test :refer [is are deftest]]
            [schema.core :as s]))

(deftest mismatched-maybe-test
  (is (nil? (sut/mismatched-maybe '(f x 2) 'x (s/maybe s/Int) (s/maybe s/Int))))
  (is (nil? (sut/mismatched-maybe '(f x 2) 'x (s/maybe s/Int) s/Int)))
  (is (nil? (sut/mismatched-maybe '(f x 2) 'x s/Int s/Int)))
  (is (nil? (sut/mismatched-maybe '(f x 2) 'x (s/maybe s/Any) s/Any)))
  (is (nil? (sut/mismatched-maybe '(f x 2) 'x s/Any (s/maybe s/Any))))

  (is (not (nil? (sut/mismatched-maybe '(f 1 2) 'x s/Int (s/maybe s/Int))))))

(deftest mismatched-ground-types-test
  (is (nil? (sut/mismatched-ground-types '(f x 2) 'x (s/maybe s/Int) s/Str)))
  (is (nil? (sut/mismatched-ground-types '(f x 2) 'x s/Int (s/maybe s/Str))))
  (is (nil? (sut/mismatched-ground-types '(f x 2) 'x s/Any s/Int)))
  (is (nil? (sut/mismatched-ground-types '(f x 2) 'x s/Int s/Any)))

  (is (not (nil? (sut/mismatched-ground-types '(f x 2) 'x s/Int s/Str))))
  (is (not (nil? (sut/mismatched-ground-types '(f x 2) 'x s/Str s/Int))))
  (is (not (nil? (sut/mismatched-ground-types '(f x 2) 'x s/Bool s/Symbol))))
  (is (not (nil? (sut/mismatched-ground-types '(f x 2) 'x s/Bool s/Int)))))

(deftest mismatched-key-schemas
  (is (empty? (sut/apply-mismatches-by-key nil nil {s/Keyword s/Int} {s/Keyword s/Int})))
  (is (empty? (sut/apply-mismatches-by-key nil nil {s/Keyword (s/maybe s/Int)} {s/Keyword (s/maybe s/Int)})))
  (is (empty? (sut/apply-mismatches-by-key nil nil {s/Keyword (s/maybe s/Int)} {s/Keyword s/Int})))
  (is (empty? (sut/apply-mismatches-by-key nil nil {s/Keyword s/Int s/Str s/Str} {s/Keyword s/Int s/Str s/Str})))

  (is (seq (sut/apply-mismatches-by-key nil nil {s/Keyword s/Int} {s/Keyword (s/maybe s/Int)})))
  (is (seq (sut/apply-mismatches-by-key nil nil {s/Keyword s/Int} {s/Keyword s/Str})))
  (is (seq (sut/apply-mismatches-by-key nil nil {s/Keyword s/Int s/Str s/Str} {s/Keyword s/Int s/Str s/Int})))
  (is (seq (sut/apply-mismatches-by-key nil nil {s/Keyword s/Int s/Str s/Str} {s/Keyword s/Str s/Str s/Str}))))

(deftest check-keys-test
  (is (nil? (sut/check-keys nil nil {s/Keyword s/Int} {s/Keyword s/Int})))
  (is (nil? (sut/check-keys nil nil  {(s/optional-key s/Keyword) s/Int} {})))

  (is (not (nil? (sut/check-keys nil nil {(s/optional-key s/Keyword) s/Int} {s/Keyword s/Int}))))
  (is (not (nil? (sut/check-keys nil nil {} {s/Keyword s/Int})))))
