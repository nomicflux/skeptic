(ns skeptic.analysis.types-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.types :as sut]
            [skeptic.analysis.types.schema :as types-schema]
            [skeptic.provenance :as prov]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def ^:private p-schema
  (prov/make-provenance :schema 'a/b 'a nil [] :clj))

(def ^:private p-native
  (prov/make-provenance :native 'x/y 'x nil [] :clj))

(deftest class-integral?-test
  (is (true? (sut/class-integral? (oracle/host-handle Long))))
  (is (false? (sut/class-integral? (oracle/host-handle String)))))

(deftest constructors-attach-provenance
  (testing "every constructor attaches its prov arg"
    (is (= p-schema (prov/of (sut/->DynT p-schema))))
    (is (= p-schema (prov/of (sut/->BottomT p-schema))))
    (is (= p-schema (prov/of (sut/->GroundT p-schema :int 'Int))))
    (is (= p-schema (prov/of (sut/->NumericDynT p-schema))))
    (is (= p-schema (prov/of (sut/->RefinementT p-schema (sut/Dyn p-schema) 'X nil nil))))
    (is (= p-schema (prov/of (sut/->AdapterLeafT p-schema nil 'X nil nil))))
    (is (= p-schema (prov/of (sut/->OptionalKeyT p-schema (sut/Dyn p-schema)))))
    (is (= p-schema (prov/of (sut/->FnMethodT p-schema [] (sut/Dyn p-schema) 0 false []))))
    (is (= p-schema (prov/of (sut/->FunT p-schema []))))
    (is (= p-schema (prov/of (sut/->MaybeT p-schema (sut/Dyn p-schema)))))
    (is (= p-schema (prov/of (sut/->UnionT p-schema #{}))))
    (is (= p-schema (prov/of (sut/->IntersectionT p-schema #{}))))
    (is (= p-schema (prov/of (sut/->MapT p-schema {}))))
    (is (= p-schema (prov/of (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [] nil) :vector))))
    (is (= p-schema (prov/of (sut/->SetT p-schema #{} true))))
    (is (= p-schema (prov/of (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [] nil) :sequential))))
    (is (= p-schema (prov/of (sut/->VarT p-schema (sut/Dyn p-schema)))))
    (is (= p-schema (prov/of (sut/->PlaceholderT p-schema 'foo))))
    (is (= p-schema (prov/of (sut/->InfCycleT p-schema 'foo))))
    (is (= p-schema (prov/of (sut/->ValueT p-schema (sut/Dyn p-schema) 1))))
    (is (= p-schema (prov/of (sut/->TypeVarT p-schema 'T))))
    (is (= p-schema (prov/of (sut/->ForallT p-schema ['T] (sut/Dyn p-schema)))))
    (is (= p-schema (prov/of (sut/->SealedDynT p-schema (sut/->TypeVarT p-schema 'T)))))
    (is (= p-schema (prov/of (sut/->ConditionalT p-schema []))))))

(deftest singleton-wrappers-carry-prov
  (is (= p-schema (prov/of (sut/Dyn p-schema))))
  (is (= p-native (prov/of (sut/BottomType p-native))))
  (is (= p-schema (prov/of (sut/NumericDyn p-schema)))))

(deftest structural-equality-ignores-prov
  (testing "two types with same body but different prov are type=? (prov is not part of structural identity)"
    (is (sut/type=? (sut/->GroundT p-schema :int 'Int)
                    (sut/->GroundT p-native :int 'Int))))
  (testing "different bodies are not type=? even with same prov"
    (is (not (sut/type=? (sut/->GroundT p-schema :int 'Int)
                         (sut/->GroundT p-schema :str 'Str))))))

(deftest nested-structural-equality-ignores-prov
  (let [schema-int (sut/->GroundT p-schema :int 'Int)
        native-int (sut/->GroundT p-native :int 'Int)
        schema-str (sut/->GroundT p-schema :str 'Str)
        native-str (sut/->GroundT p-native :str 'Str)]
    (testing "collections recurse through semantic type comparison"
      (is (sut/type=? [(sut/->MaybeT p-schema schema-int)]
                      [(sut/->MaybeT p-native native-int)]))
      (is (sut/type=? [(sut/->MaybeT p-schema schema-int)]
                      (list (sut/->MaybeT p-native native-int))))
      (is (sut/type=? #{(sut/->ValueT p-schema schema-str :a)}
                      #{(sut/->ValueT p-native native-str :a)}))
      (is (sut/type=? {(sut/->ValueT p-schema schema-str :a) schema-int}
                      {(sut/->ValueT p-native native-str :a) native-int})))
    (testing "semantic containers recurse through their fields"
      (is (sut/type=? (sut/->MapT p-schema {(sut/->ValueT p-schema schema-str :a) schema-int})
                      (sut/->MapT p-native {(sut/->ValueT p-native native-str :a) native-int})))
      (is (sut/type=? (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [schema-int schema-str] nil) :vector)
                      (sut/->SeqT p-native (sut/pattern-from-prefix-tail [native-int native-str] nil) :vector)))
      (is (sut/type=? (sut/->SetT p-schema #{schema-int schema-str} false)
                      (sut/->SetT p-native #{native-str native-int} false)))
      (is (sut/type=? (sut/->UnionT p-schema #{schema-int schema-str})
                      (sut/->UnionT p-native #{native-str native-int})))
      (is (sut/type=? (sut/->IntersectionT p-schema #{schema-int schema-str})
                      (sut/->IntersectionT p-native #{native-str native-int}))))
    (testing "function, quantified, and conditional shapes compare by semantic fields"
      (is (sut/type=? (sut/->FunT p-schema [(sut/->FnMethodT p-schema [schema-int] schema-str 1 false ['x])])
                      (sut/->FunT p-native [(sut/->FnMethodT p-native [native-int] native-str 1 false ['x])])))
      (is (sut/type=? (sut/->ForallT p-schema ['T] (sut/->TypeVarT p-schema 'T))
                      (sut/->ForallT p-native ['T] (sut/->TypeVarT p-native 'T))))
      (is (sut/type=? (sut/->ConditionalT p-schema [(sut/->ConditionalBranch :truthy schema-int nil nil)
                                                    (sut/->ConditionalBranch :else schema-str nil nil)])
                      (sut/->ConditionalT p-native [(sut/->ConditionalBranch :truthy native-int nil nil)
                                                    (sut/->ConditionalBranch :else native-str nil nil)]))))
    (testing "non-provenance differences still matter"
      (is (not (sut/type=? (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [] schema-int) :vector)
                           (sut/->SeqT p-native (sut/pattern-from-prefix-tail [native-int] nil) :vector))))
      (is (not (sut/type=? (sut/->ValueT p-schema schema-str :a)
                           (sut/->ValueT p-native native-str :b)))))))

(deftest type=-ignores-runtime-closures-and-prov
  (let [schema-base (sut/->GroundT p-schema :int 'Int)
        native-base (sut/->GroundT p-native :int 'Int)]
    (is (sut/type=? (sut/->RefinementT p-schema schema-base 'Positive pos? {:tag :same})
                    (sut/->RefinementT p-native native-base 'Positive neg? {:tag :same})))))

(deftest dedup-types-uses-semantic-comparison-and-preserves-originals
  (let [schema-int (sut/->GroundT p-schema :int 'Int)
        native-int (sut/->GroundT p-native :int 'Int)
        schema-str (sut/->GroundT p-schema :str 'Str)
        deduped (sut/dedup-types [schema-int schema-str native-int])]
    (is (= 2 (count deduped)))
    (is (contains? deduped native-int))
    (is (not (contains? deduped schema-int)))
    (is (contains? deduped schema-str))
    (is (every? #(some? (prov/of %)) deduped))))

(deftest dedup-types-handles-hash-collisions-with-type-comparison
  (let [a (sut/->ValueT p-schema (sut/->GroundT p-schema :int 'Int) 0)
        b (sut/->ValueT p-schema (sut/->GroundT p-schema :int 'Int) 1)]
    (with-redefs-fn {#'sut/type-hash (constantly 0)}
      (fn []
        (let [deduped (sut/dedup-types [a b])]
          (is (= 2 (count deduped)))
          (is (contains? deduped a))
          (is (contains? deduped b)))))))

(deftest predicates-unaffected-by-metadata
  (is (sut/ground-type? (sut/->GroundT p-schema :int 'Int)))
  (is (sut/fun-type? (sut/->FunT p-schema [])))
  (is (sut/maybe-type? (sut/->MaybeT p-schema (sut/Dyn p-schema)))))

(deftest regex-atom-helpers
  (let [int-t (sut/->GroundT p-schema :int 'Int)
        str-t (sut/->GroundT p-schema :str 'Str)]
    (testing "one-atom and star-atom build the documented tagged-map shape"
      (is (= {:kind :one :type int-t} (sut/one-atom int-t)))
      (is (= {:kind :star :type str-t} (sut/star-atom str-t))))
    (testing "predicates and accessor"
      (is (sut/one-atom? (sut/one-atom int-t)))
      (is (sut/star-atom? (sut/star-atom int-t)))
      (is (not (sut/one-atom? (sut/star-atom int-t))))
      (is (not (sut/star-atom? (sut/one-atom int-t))))
      (is (= int-t (sut/atom-type (sut/one-atom int-t))))
      (is (= str-t (sut/atom-type (sut/star-atom str-t)))))))

(deftest pattern-prefix-and-pattern-tail-roundtrip
  (let [int-t (sut/->GroundT p-schema :int 'Int)
        str-t (sut/->GroundT p-schema :str 'Str)]
    (testing "empty pattern: prefix=[] tail=nil"
      (let [p (sut/pattern-from-prefix-tail [] nil)]
        (is (= [] (sut/pattern-prefix p)))
        (is (nil? (sut/pattern-tail p)))))
    (testing "closed prefix only: items=[Int Str] tail=nil"
      (let [p (sut/pattern-from-prefix-tail [int-t str-t] nil)]
        (is (= [int-t str-t] (sut/pattern-prefix p)))
        (is (nil? (sut/pattern-tail p)))))
    (testing "homogeneous only: items=[] tail=Int"
      (let [p (sut/pattern-from-prefix-tail [] int-t)]
        (is (= [] (sut/pattern-prefix p)))
        (is (= int-t (sut/pattern-tail p)))))
    (testing "prefix plus tail: items=[Int Str] tail=Int"
      (let [p (sut/pattern-from-prefix-tail [int-t str-t] int-t)]
        (is (= [int-t str-t] (sut/pattern-prefix p)))
        (is (= int-t (sut/pattern-tail p)))))))

(deftest pattern-schema-accepts-valid-shapes
  (let [int-t (sut/->GroundT p-schema :int 'Int)]
    (is (nil? (s/check types-schema/Pattern [])))
    (is (nil? (s/check types-schema/Pattern [(sut/one-atom int-t)])))
    (is (nil? (s/check types-schema/Pattern [(sut/one-atom int-t) (sut/star-atom int-t)])))
    (is (nil? (s/check types-schema/Pattern [(sut/star-atom int-t)])))))

(deftest pattern-schema-rejects-star-not-last
  (let [int-t (sut/->GroundT p-schema :int 'Int)]
    (is (some? (s/check types-schema/Pattern [(sut/star-atom int-t) (sut/one-atom int-t)])))
    (is (some? (s/check types-schema/Pattern [(sut/star-atom int-t) (sut/star-atom int-t)])))))

(deftest seqt-type=-uses-pattern-shape
  (let [schema-int (sut/->GroundT p-schema :int 'Int)
        native-int (sut/->GroundT p-native :int 'Int)]
    (testing "two SeqTs with same pattern shape compare equal regardless of prov"
      (is (sut/type=? (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [schema-int] schema-int) :vector)
                      (sut/->SeqT p-native (sut/pattern-from-prefix-tail [native-int] native-int) :vector))))
    (testing "different atom kinds compare unequal"
      (is (not (sut/type=? (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [schema-int] nil) :vector)
                           (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [] schema-int) :vector)))))
    (testing "different ordered-coll-kind compare unequal"
      (is (not (sut/type=? (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [schema-int] nil) :vector)
                           (sut/->SeqT p-schema (sut/pattern-from-prefix-tail [schema-int] nil) :sequential)))))))

