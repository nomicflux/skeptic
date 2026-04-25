(ns skeptic.analysis.predicates-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.predicates :as predicates]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.provenance :as prov]))

(defn- prov [] (prov/make-provenance :native 'test-sym nil nil))

(deftest predicate?-test
  (is (predicates/predicate? 'clojure.core/string?))
  (is (predicates/predicate? 'string?))
  (is (not (predicates/predicate? 'clojure.core/some?)))
  (is (not (predicates/predicate? 'foo))))

(deftest resolve-predicate-symbol-test
  (is (= 'clojure.core/string? (predicates/resolve-predicate-symbol 'string?)))
  (is (= 'clojure.core/string? (predicates/resolve-predicate-symbol 'clojure.core/string?)))
  (is (nil? (predicates/resolve-predicate-symbol 'foo))))

(deftest predicate-fn-type-test
  (let [t (predicates/predicate-fn-type 'clojure.core/string?)]
    (is (instance? skeptic.analysis.types.FunT t))
    (let [methods (:methods t)]
      (is (= 1 (count methods)))
      (let [m (first methods)]
        (is (= 1 (:min-arity m)))
        (is (= false (:variadic? m)))
        (is (= 1 (count (:inputs m))))
        (is (instance? skeptic.analysis.types.DynT (first (:inputs m))))
        (is (= :bool (:ground (:output m))))))))

(deftest witness-type-test
  (let [p (prov)]
    (testing "string? -> Str ground"
      (let [w (predicates/witness-type 'clojure.core/string? p)]
        (is (instance? skeptic.analysis.types.GroundT w))
        (is (= :str (:ground w)))))
    (testing "pos? -> NumericDyn"
      (is (instance? skeptic.analysis.types.NumericDynT
                     (predicates/witness-type 'clojure.core/pos? p))))
    (testing "nil? matches (s/eq nil) shape"
      (is (= (ato/exact-value-type p nil)
             (predicates/witness-type 'clojure.core/nil? p))))))
