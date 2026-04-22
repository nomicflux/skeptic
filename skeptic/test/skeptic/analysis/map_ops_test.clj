(ns skeptic.analysis.map-ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.provenance :as prov]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest domain-query-stores-semantic-type-test
  (let [query (amo/domain-key-query (ab/schema->type tp s/Keyword) 'k)]
    (is (amo/map-key-query? query))
    (is (= :domain (:kind query)))
    (is (contains? query :type))
    (is (not (contains? query :schema)))
    (is (= (ab/schema->type tp s/Keyword)
           (amo/query-key-type query)))))

(deftest explicit-map-key-query-contract-test
  (let [exact-query (amo/exact-key-query nil :a :a)
        optional-query (amo/exact-key-query nil :a :a)
        domain-query (amo/domain-key-query (ab/schema->type tp s/Keyword) 'k)]
    (is (= :exact (:kind exact-query)))
    (is (= :a (:value exact-query)))
    (is (= :exact (:kind optional-query)))
    (is (= :a (:value optional-query)))
    (is (= :domain (:kind domain-query)))
    (is (= (ab/schema->type tp s/Keyword)
           (amo/query-key-type domain-query)))))

(deftest map-lookup-and-map-get-type-regression-test
  (let [mtype (ab/schema->type tp {:a s/Int
                                s/Keyword s/Str})
        entries (:entries mtype)
        exact-query (amo/exact-key-query (ab/schema->type tp s/Keyword) :a :a)
        domain-query (amo/domain-key-query (ab/schema->type tp s/Keyword) 'k)
        descriptor (amo/map-entry-descriptor entries)]
    (is (= :required-explicit
           (-> descriptor (amo/exact-key-entry :a) :kind)))
    (is (= 1 (count (amo/map-lookup-candidates entries exact-query))))
    (is (= (ab/schema->type tp s/Int)
           (amo/map-get-type mtype exact-query)))
    (is (= (ab/schema->type tp (sb/join s/Int s/Str))
           (amo/map-get-type mtype domain-query)))))

(deftest merge-map-types-regression-test
  (testing "semantic map merge stays structural"
    (is (= (ab/schema->type tp {:a s/Int :b s/Int})
           (amo/merge-map-types [(ab/schema->type tp {:a s/Int})
                                 (ab/schema->type tp {:b s/Int})]))))
  (testing "non-map inputs stay dynamic"
    (is (= (at/Dyn tp)
           (amo/merge-map-types [(ab/schema->type tp {:a s/Int})
                                 (ab/schema->type tp s/Int)])))))

(deftest semantic-map-query-regression-test
  (let [mtype (ab/schema->type tp {:a s/Int
                                s/Keyword s/Str})
        entries (:entries mtype)
        domain-query (amo/domain-key-query (ab/schema->type tp s/Keyword) 'k)]
    (is (= (ab/schema->type tp (sb/join s/Int s/Str))
           (amo/map-get-type mtype domain-query)))
    (is (= 2 (count (amo/map-lookup-candidates entries domain-query))))))
