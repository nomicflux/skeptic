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

(deftest predicates-unaffected-by-metadata
  (is (sut/ground-type? (sut/->GroundT p-schema :int 'Int)))
  (is (sut/fun-type? (sut/->FunT p-schema [])))
  (is (sut/maybe-type? (sut/->MaybeT p-schema (sut/Dyn p-schema)))))
