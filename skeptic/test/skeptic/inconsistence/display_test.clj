(ns skeptic.inconsistence.display-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.types :as at]
            [skeptic.inconsistence.display :as sut]))

(deftest ppr-str-test
  (is (str/includes? (sut/ppr-str {:a 1}) ":a"))
  (is (str/includes? (sut/ppr-str [1 2 3]) "1")))

(deftest public-ref-form-test
  (is (= 'foo (sut/public-ref-form 'foo)))
  (is (= 'bar (sut/public-ref-form '[foo bar])))
  (is (= 'kw (sut/public-ref-form :kw)))
  (is (= 's (sut/public-ref-form "s")))
  (is (= 'Unknown (sut/public-ref-form 42))))

(deftest literal-form-test
  (is (nil? (sut/literal-form (reify Object (toString [_] "x")))))
  (is (= :k (sut/literal-form :k)))
  (is (= [1 2] (sut/literal-form [1 2])))
  (is (= {:a 1} (sut/literal-form {:a 1}))))

(deftest exact-key-form-test
  (is (= :a (sut/exact-key-form :a)))
  (is (= :a (sut/exact-key-form (s/optional-key :a))))
  (is (= :a (sut/exact-key-form {:cleaned-key (s/optional-key :a)})))
  (is (= :a (sut/exact-key-form (at/->OptionalKeyT (at/->ValueT (at/->GroundT :keyword 'Keyword) :a))))))

(deftest format-and-block-user-form-test
  (is (= ":x" (sut/format-user-form :x)))
  (is (some? (sut/pretty-user-form {:a 1 :b 2})))
  (is (string? (sut/block-user-form :short))))

(deftest user-type-and-domain-form-test
  (is (= 'Int (sut/user-type-form (ab/schema->type s/Int))))
  (is (= false (sut/user-type-form (ab/schema->type (s/eq false)))))
  (is (= (symbol "nil") (sut/user-type-form (ab/schema->type (s/eq nil)))))
  (is (= 'Str (sut/user-schema-form s/Str)))
  (is (= :kw (sut/user-raw-form :kw))))

(deftest describe-display-helpers-test
  (is (= "Int" (sut/describe-type (ab/schema->type s/Int))))
  (is (string? (sut/describe-type-block (ab/schema->type s/Int))))
  (is (= "Str" (sut/describe-schema s/Str)))
  (is (= ":kw" (sut/describe-raw :kw)))
  (is (= ":kw" (sut/describe-item :kw))))

(deftest user-fn-input-form-test
  (let [m (at/->FnMethodT [(ab/schema->type s/Int) (ab/schema->type s/Str)]
                          (ab/schema->type s/Bool)
                          2
                          false
                          '[x y])]
    (is (= ['Int 'Str] (vec (sut/user-fn-input-form m)))))
  (let [v (at/->FnMethodT [(ab/schema->type s/Int) (ab/schema->type s/Str)]
                          (ab/schema->type s/Bool)
                          1
                          true
                          '[x y])
        r (vec (sut/user-fn-input-form v))]
    (is (= 'Int (first r)))
    (is (= '& (second r)))
    (is (= ['Str] (vec (nth r 2))))))
