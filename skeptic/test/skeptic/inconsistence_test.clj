(ns skeptic.inconsistence-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.schema :as as]
            [skeptic.analysis.schema.value-check :as asv]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(defn schema-or-value
  [schema value]
  (sb/valued-schema schema value))

(deftest directional-cast-kernel-test
  (let [exact (as/check-cast s/Int s/Int)
        target-dyn (as/check-cast s/Int s/Any)
        nested-dyn (as/check-cast {:a s/Any} {:a s/Int})
        target-union (as/check-cast s/Int (s/either s/Int s/Str))
        source-union (as/check-cast (s/either s/Int s/Str) s/Int)
        nilability (as/check-cast (s/maybe s/Any) s/Int)
        domain-failure (as/check-cast (s/=> s/Int s/Int)
                                      (s/=> s/Int s/Str))
        domain-child (first (-> domain-failure :children first :children))
        range-failure (as/check-cast (s/=> s/Str s/Int)
                                     (s/=> s/Int s/Int))
        range-child (last (-> range-failure :children first :children))
        target-intersection (as/check-cast s/Int (s/both s/Any s/Int))]
    (is (:ok? exact))
    (is (= :exact (:rule exact)))

    (is (:ok? target-dyn))
    (is (= :target-dyn (:rule target-dyn)))

    (is (:ok? nested-dyn))
    (is (= :map (:rule nested-dyn)))
    (is (= :residual-dynamic (-> nested-dyn :children first :rule)))

    (is (:ok? target-union))
    (is (= :target-union (:rule target-union)))

    (is (not (:ok? source-union)))
    (is (= :source-union (:rule source-union)))

    (is (not (:ok? nilability)))
    (is (= :maybe-source (:rule nilability)))

    (is (not (:ok? domain-failure)))
    (is (= :negative (:blame-polarity domain-child)))

    (is (not (:ok? range-failure)))
    (is (= :positive (:blame-polarity range-child)))

    (is (:ok? target-intersection))
    (is (= :target-intersection (:rule target-intersection)))))

(deftest semantic-function-type-rendering-test
  (let [fun-type (at/->FunT [(at/->FnMethodT [(ab/schema->type s/Int)]
                                             (ab/intersection-type [s/Any s/Int])
                                             1
                                             false)])
        polymorphic-fun (at/->FunT [(at/->FnMethodT [(at/->TypeVarT 'X)]
                                                    (at/->SealedDynT (at/->TypeVarT 'X))
                                                    1
                                                    false)])]
    (is (= fun-type (ab/schema->type fun-type)))
    (is (= "(=> (intersection Any Int) Int)"
           (abr/render-type fun-type)))
    (is (= "(=> (sealed X) X)"
           (abr/render-type polymorphic-fun)))))

(deftest valued-helper-logic-lives-in-analysis-schema-test
  (is (= 1 (as/get-by-matching-schema {s/Symbol 1} clojure.lang.Symbol)))
  (is (= 2 (as/get-by-matching-schema {s/Int 2} java.lang.Integer)))

  (is (= 1 (as/valued-get {:a 1 :b 2} :a)))
  (is (= 1 (as/valued-get {:a 1 :b 2} (schema-or-value s/Keyword :a))))
  (is (= 2 (as/valued-get {:a 1 s/Keyword 2} (schema-or-value s/Keyword :b))))

  (is (as/valued-compatible? {s/Keyword s/Int :b s/Str} {:a 1 :b "x"}))
  (is (not (as/valued-compatible? {s/Keyword s/Int :b s/Str} {:b 1 :a "x"})))
  (is (as/valued-compatible? {:name s/Str
                              :schema (s/maybe s/Any)}
                             {(schema-or-value s/Keyword :name) (schema-or-value s/Str "x")
                              (schema-or-value s/Keyword :schema) (schema-or-value s/Int 1)}))

  (is (as/matches-map {s/Keyword s/Str :b 2} :a "x"))
  (is (not (as/matches-map {s/Keyword s/Str :b 2} :a 1))))

(deftest mixed-map-schema-requiredness-regression-test
  (let [schema {:a s/Int :b s/Str s/Keyword s/Any}
        valid {:a 1 :b "hello"}
        valid-with-extra {:a 1 :b "hello" :c 5}
        missing-required {:a 1}
        cast-result (as/check-cast {:a s/Int :b s/Str} schema)]
    (is (nil? (s/check schema valid)))
    (is (nil? (s/check schema valid-with-extra)))
    (is (some? (s/check schema missing-required)))

    (is (asv/value-satisfies-type? valid schema))
    (is (asv/value-satisfies-type? valid-with-extra schema))
    (is (not (asv/value-satisfies-type? missing-required schema)))

    (is (:ok? cast-result))
    (is (= :map (:rule cast-result)))))

(deftest extra-schema-row-does-not-imply-required-presence-regression-test
  (let [schema {s/Keyword s/Int}
        empty-value {}
        cast-result (as/check-cast empty-value schema)]
    (is (nil? (s/check schema empty-value)))
    (is (asv/value-satisfies-type? empty-value schema))
    (is (:ok? cast-result))
    (is (= :map (:rule cast-result)))))

(deftest pattern-map-key-presence-classification-regression-test
  (is (= :unknown
         (asv/contains-key-classification {s/Keyword s/Any} :a)))
  (is (= :always
         (asv/contains-key-classification {:a s/Int s/Keyword s/Any} :a)))
  (is (= :unknown
         (asv/contains-key-classification {:a s/Int s/Keyword s/Any} :b))))
