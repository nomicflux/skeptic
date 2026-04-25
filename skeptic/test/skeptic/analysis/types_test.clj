(ns skeptic.analysis.types-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.types :as sut]
            [skeptic.provenance :as prov]))

(def ^:private p-schema
  (prov/make-provenance :schema 'a/b 'a nil))

(def ^:private p-native
  (prov/make-provenance :native 'x/y 'x nil))

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
    (is (= p-schema (prov/of (sut/->UnionT p-schema []))))
    (is (= p-schema (prov/of (sut/->IntersectionT p-schema []))))
    (is (= p-schema (prov/of (sut/->MapT p-schema {}))))
    (is (= p-schema (prov/of (sut/->VectorT p-schema [] true))))
    (is (= p-schema (prov/of (sut/->SetT p-schema #{} true))))
    (is (= p-schema (prov/of (sut/->SeqT p-schema [] true))))
    (is (= p-schema (prov/of (sut/->VarT p-schema (sut/Dyn p-schema)))))
    (is (= p-schema (prov/of (sut/->PlaceholderT p-schema 'foo))))
    (is (= p-schema (prov/of (sut/->InfCycleT p-schema 'foo))))
    (is (= p-schema (prov/of (sut/->ValueT p-schema (sut/Dyn p-schema) 1))))
    (is (= p-schema (prov/of (sut/->TypeVarT p-schema 'T))))
    (is (= p-schema (prov/of (sut/->ForallT p-schema ['T] (sut/Dyn p-schema)))))
    (is (= p-schema (prov/of (sut/->SealedDynT p-schema (sut/Dyn p-schema)))))
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
      (is (sut/type=? (sut/->VectorT p-schema [schema-int schema-str] false)
                      (sut/->VectorT p-native [native-int native-str] false)))
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
      (is (sut/type=? (sut/->ConditionalT p-schema [[:truthy schema-int] [:else schema-str]])
                      (sut/->ConditionalT p-native [[:truthy native-int] [:else native-str]]))))
    (testing "non-provenance differences still matter"
      (is (not (sut/type=? (sut/->VectorT p-schema [schema-int] true)
                           (sut/->VectorT p-native [native-int] false))))
      (is (not (sut/type=? (sut/->ValueT p-schema schema-str :a)
                           (sut/->ValueT p-native native-str :b)))))))

(deftest type-equal-ignores-runtime-closures-and-prov
  (let [schema-base (sut/->GroundT p-schema :int 'Int)
        native-base (sut/->GroundT p-native :int 'Int)]
    (is (sut/type-equal? (sut/->RefinementT p-schema schema-base 'Positive pos? {:tag :same})
                         (sut/->RefinementT p-native native-base 'Positive neg? {:tag :same})))
    (is (sut/type-equal? (sut/->ConditionalT p-schema [[pos? schema-base]])
                         (sut/->ConditionalT p-native [[neg? native-base]])))))

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
