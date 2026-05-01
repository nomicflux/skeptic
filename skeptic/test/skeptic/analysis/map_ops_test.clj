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
    (is (at/type=? (ab/schema->type tp s/Keyword)
           (amo/query-key-type query)))))

(deftest explicit-map-key-query-contract-test
  (let [exact-query (amo/exact-key-query tp :a :a)
        optional-query (amo/exact-key-query tp :a :a)
        domain-query (amo/domain-key-query (ab/schema->type tp s/Keyword) 'k)]
    (is (= :exact (:kind exact-query)))
    (is (= :a (:value exact-query)))
    (is (= :exact (:kind optional-query)))
    (is (= :a (:value optional-query)))
    (is (= :domain (:kind domain-query)))
    (is (at/type=? (ab/schema->type tp s/Keyword)
           (amo/query-key-type domain-query)))))

(deftest map-lookup-and-map-get-type-regression-test
  (let [mtype (ab/schema->type tp {:a s/Int
                                s/Keyword s/Str})
        entries (:entries mtype)
        exact-query (amo/exact-key-query tp :a :a)
        domain-query (amo/domain-key-query (ab/schema->type tp s/Keyword) 'k)
        descriptor (amo/map-entry-descriptor entries)]
    (is (= :required-explicit
           (-> descriptor (amo/exact-key-entry :a) :kind)))
    (is (= 1 (count (amo/map-lookup-candidates entries exact-query))))
    (is (at/type=? (ab/schema->type tp s/Int)
           (amo/map-get-type mtype exact-query)))
    (is (at/type=? (ab/schema->type tp (sb/join s/Int s/Str))
           (amo/map-get-type mtype domain-query)))))

(deftest merge-map-types-regression-test
  (testing "semantic map merge stays structural"
    (is (at/type=? (ab/schema->type tp {:a s/Int :b s/Int})
           (amo/merge-map-types tp [(ab/schema->type tp {:a s/Int})
                                    (ab/schema->type tp {:b s/Int})]))))
  (testing "non-map inputs stay dynamic"
    (is (at/type=? (at/Dyn tp)
           (amo/merge-map-types tp [(ab/schema->type tp {:a s/Int})
                                    (ab/schema->type tp s/Int)])))))

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil))

(deftest merge-map-types-container-owns-prov-test
  (testing "empty types with anchor does not crash and carries anchor prov"
    (let [result (amo/merge-map-types tp [])]
      (is (at/type=? (at/Dyn tp) result))
      (is (= tp (prov/of result)))))
  (testing "result prov is the anchor, not derived from input map provs"
    (let [m1 (at/->MapT other-prov {})
          m2 (at/->MapT other-prov {})
          result (amo/merge-map-types tp [m1 m2])
          result-prov (prov/of result)]
      (is (at/map-type? result))
      (is (= (:source tp) (:source result-prov)))
      (is (= (:qualified-sym tp) (:qualified-sym result-prov)))
      (is (= (:declared-in tp) (:declared-in result-prov))))))

(deftest semantic-map-query-regression-test
  (let [mtype (ab/schema->type tp {:a s/Int
                                s/Keyword s/Str})
        entries (:entries mtype)
        domain-query (amo/domain-key-query (ab/schema->type tp s/Keyword) 'k)]
    (is (at/type=? (ab/schema->type tp (sb/join s/Int s/Str))
           (amo/map-get-type mtype domain-query)))
    (is (= 2 (count (amo/map-lookup-candidates entries domain-query))))))

(deftest merge-map-types-threads-refs-test
  (testing "merging two map types: refs has 2 entries (one per constituent map)"
    (let [m1 (at/->MapT tp {(at/->ValueT tp (at/->GroundT tp :keyword 'Keyword) :a)
                            (at/->GroundT tp :int 'Int)})
          m2 (at/->MapT tp {(at/->ValueT tp (at/->GroundT tp :keyword 'Keyword) :b)
                            (at/->GroundT tp :int 'Int)})
          result (amo/merge-map-types tp [m1 m2])
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest refine-map-path-by-values-map-shape
  (let [root (ab/schema->type tp {:x {:k s/Str}})
        path [(amo/exact-key-query tp :x :x) (amo/exact-key-query tp :k :k)]
        result (amo/refine-map-path-by-values root path ["b"] true)]
    (is (at/map-type? result))
    (let [x-val (amo/map-get-type result (amo/exact-key-query tp :x :x))
          k-val (amo/map-get-type x-val (amo/exact-key-query tp :k :k))]
      (is (at/value-type? k-val))
      (is (= "b" (:value k-val))))))

(deftest refine-map-path-by-values-union-shape
  (let [branch-a (ab/schema->type tp {:x {:k (s/eq "a")}})
        branch-b (ab/schema->type tp {:x {:k (s/eq "b")}})
        union-root (at/->UnionT tp [branch-a branch-b])
        path [(amo/exact-key-query tp :x :x) (amo/exact-key-query tp :k :k)]
        result (amo/refine-map-path-by-values union-root path ["b"] true)]
    (is (not (at/bottom-type? result)))))

(deftest refine-map-path-by-values-maybe-shape
  (let [inner (ab/schema->type tp {:x {:k s/Str}})
        root (at/->MaybeT tp inner)
        path [(amo/exact-key-query tp :x :x) (amo/exact-key-query tp :k :k)]
        result (amo/refine-map-path-by-values root path ["b"] true)]
    (is (not (at/bottom-type? result)))
    (is (at/maybe-type? result))))

(deftest refine-map-path-by-values-conditional-shape
  (let [path [(amo/exact-key-query tp :x :x) (amo/exact-key-query tp :k :k)]
        branch-a-type (ab/schema->type tp {:x {:k (s/eq "a")} :disc "a"})
        branch-b-type (ab/schema->type tp {:x {:k (s/eq "b")} :disc "b"})
        pred-a (fn [m] (= (get-in m [:x :k]) "a"))
        pred-b (fn [m] (= (get-in m [:x :k]) "b"))
        cond-type (at/->ConditionalT tp [[pred-a branch-a-type] [pred-b branch-b-type]])
        result (amo/refine-map-path-by-values cond-type path ["b"] true)]
    (is (not (at/bottom-type? result)))
    (is (at/map-type? result))
    (is (= "b" (:value (amo/map-get-type result (amo/exact-key-query tp :disc :disc)))))))

(deftest refine-by-contains-key-promotes-optional-key
  (let [root (ab/schema->type tp {(s/optional-key :k) s/Int})
        result (amo/refine-by-contains-key root :k true)
        k-val (amo/map-get-type result (amo/exact-key-query tp :k :k))]
    (is (at/map-type? result))
    (is (at/type=? (ab/schema->type tp s/Int) k-val))))

(deftest refine-by-contains-key-drops-optional-key
  (let [root (ab/schema->type tp {(s/optional-key :k) s/Int})
        result (amo/refine-by-contains-key root :k false)]
    (is (at/map-type? result))
    (is (empty? (:entries result)))))

(deftest refine-by-contains-key-required-negative-is-bottom
  (let [root (ab/schema->type tp {:k s/Int})
        result (amo/refine-by-contains-key root :k false)]
    (is (at/bottom-type? result))))

(deftest refine-by-contains-key-missing-positive-is-bottom
  (let [root (ab/schema->type tp {:other s/Int})
        result (amo/refine-by-contains-key root :k true)]
    (is (at/bottom-type? result))))

(deftest refine-by-contains-key-keeps-domain-entry-map
  (let [root (ab/schema->type tp {s/Keyword s/Int})
        result (amo/refine-by-contains-key root :k true)]
    (is (at/type=? root result))))
