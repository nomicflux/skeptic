(ns skeptic.analysis.predicates-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.predicates :as predicates]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defn- prov [] (prov/make-provenance :native 'test-sym nil nil [] :clj))

(deftest predicate?-test
  (is (predicates/predicate? 'clojure.core/string?))
  (is (predicates/predicate? 'string?))
  (is (predicates/predicate? 'map?))
  (is (not (predicates/predicate? 'clojure.core/some?)))
  (is (not (predicates/predicate? 'foo))))

(deftest resolve-predicate-symbol-test
  (is (= 'clojure.core/string? (predicates/resolve-predicate-symbol 'string?)))
  (is (= 'clojure.core/string? (predicates/resolve-predicate-symbol 'clojure.core/string?)))
  (is (= 'clojure.core/map? (predicates/resolve-predicate-symbol 'map?--4367))
      "direct-linked fn-name counters must not defeat recognition")
  (is (nil? (predicates/resolve-predicate-symbol 'foo))))

(deftest demunged-predicate-symbol-test
  (is (= 'map? (predicates/demunged-predicate-symbol 'map?--4367)))
  (is (= 'clojure.core/map? (predicates/demunged-predicate-symbol 'clojure.core/map?--4367)))
  (is (= 'map? (predicates/demunged-predicate-symbol 'map?))))

(deftest resolve-predicate-symbol-output-schema-is-maybe-symbol
  (let [fs (s/fn-schema predicates/resolve-predicate-symbol)
        out (:output-schema fs)]
    (is (= (s/maybe s/Symbol) out)
        (str "expected output schema (maybe Symbol); got: " (pr-str out)))))

(deftest predicate-fn-type-test
  (let [t (predicates/predicate-fn-type 'clojure.core/string? :clj)]
    (is (at/fun-type? t))
    (let [methods (:methods t)]
      (is (= 1 (count methods)))
      (let [m (first methods)]
        (is (= 1 (:min-arity m)))
        (is (= false (:variadic? m)))
        (is (= 1 (count (:inputs m))))
        (is (at/dyn-type? (first (:inputs m))))
        (is (= :bool (:ground (:output m))))))))

(deftest witness-type-test
  (let [p (prov)]
    (testing "string? -> Str ground"
      (let [w (predicates/witness-type 'clojure.core/string? p)]
        (is (at/ground-type? w))
        (is (= :str (:ground w)))))
    (testing "pos? -> NumericDyn"
      (is (at/numeric-dyn-type?
           (predicates/witness-type 'clojure.core/pos? p))))
    (testing "nil? matches (s/eq nil) shape"
      (is (= (ato/exact-value-type p nil)
             (predicates/witness-type 'clojure.core/nil? p))))
    (testing "map? -> open map of Dyn -> Dyn"
      (let [w (predicates/witness-type 'clojure.core/map? p)]
        (is (at/map-type? w))
        (is (= 1 (count (:entries w))))
        (is (every? (fn [[k v]] (and (at/dyn-type? k) (at/dyn-type? v)))
                    (:entries w)))))))
