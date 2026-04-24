(ns skeptic.analysis.bridge.render-test
  (:require [clojure.test :refer [deftest is testing]]
    [skeptic.analysis.bridge.render :as sut]
    [skeptic.test-helpers :refer [tp]]
    [skeptic.analysis.types :as at]
    [skeptic.provenance :as prov]))

(defn- p
  [source sym]
  (prov/make-provenance source sym 'skeptic.test nil))

(defn- ground-int
  [prov]
  (at/->GroundT prov :int 'Int))

(defn- ground-str
  [prov]
  (at/->GroundT prov :str 'Str))

(defn- kw-key
  [prov k]
  (at/->ValueT prov (at/->GroundT prov :keyword 'Keyword) k))

(defn- named-vector
  [prov]
  (at/->VectorT prov [(ground-int prov)] false))

(deftest type->json-data-scalars
  (testing "nil passthrough"
    (is (nil? (sut/type->json-data nil))))

  (testing "Dyn and Bottom"
    (is (= {:t "any"} (sut/type->json-data (at/->DynT tp))))
    (is (= {:t "bottom"} (sut/type->json-data (at/->BottomT tp)))))

  (testing "Ground"
    (is (= {:t "ground" :name "Int"}
           (sut/type->json-data (at/->GroundT tp :int 'Int)))))

  (testing "Value"
    (is (= {:t "value" :value ":k"}
           (sut/type->json-data
            (at/->ValueT tp (at/->GroundT tp :keyword 'Keyword) :k))))))

(deftest type->json-data-constructors
  (testing "Maybe"
    (is (= {:t "maybe" :inner {:t "ground" :name "Int"}}
           (sut/type->json-data (at/->MaybeT tp (at/->GroundT tp :int 'Int))))))

  (testing "Conditional"
    (let [conditional (at/->ConditionalT tp [[integer? (at/->GroundT tp :int 'Int)]
                                          [string? (at/->MaybeT tp (at/->GroundT tp :keyword 'Keyword))]])]
      (is (= '(conditional Int (maybe Keyword))
             (sut/render-type-form conditional)))
      (is (= {:t "conditional"
              :branches [{:t "ground" :name "Int"}
                         {:t "maybe"
                          :inner {:t "ground" :name "Keyword"}}]}
             (sut/type->json-data conditional)))))

  (testing "Union sorts members for stability"
    (let [result (sut/type->json-data
                  (at/->UnionT tp #{(at/->GroundT tp :int 'Int)
                                 (at/->GroundT tp :keyword 'Keyword)}))]
      (is (= "union" (:t result)))
      (is (= [{:t "ground" :name "Int"} {:t "ground" :name "Keyword"}]
             (:members result)))))

  (testing "Vector"
    (is (= {:t "vector" :items [{:t "ground" :name "Int"}]}
           (sut/type->json-data (at/->VectorT tp [(at/->GroundT tp :int 'Int)] false)))))

  (testing "Map"
    (is (= {:t "map"
            :entries [{:key {:t "ground" :name "Keyword"}
                       :val {:t "ground" :name "Int"}}]}
           (sut/type->json-data
            (at/->MapT tp {(at/->GroundT tp :keyword 'Keyword)
                        (at/->GroundT tp :int 'Int)})))))

  (testing "OptionalKey"
    (is (= {:t "optional-key" :inner {:t "ground" :name "Int"}}
           (sut/type->json-data (at/->OptionalKeyT tp (at/->GroundT tp :int 'Int))))))

  (testing "Fun and FnMethod"
    (let [m (at/->FnMethodT tp [(at/->GroundT tp :int 'Int)]
                            (at/->GroundT tp :keyword 'Keyword)
                            1
                            false
                            '[x])]
      (is (= {:t "fn-method"
              :inputs [{:t "ground" :name "Int"}]
              :output {:t "ground" :name "Keyword"}
              :variadic false
              :min_arity 1}
             (sut/type->json-data m)))
      (is (= {:t "fun"
              :methods [{:t "fn-method"
                         :inputs [{:t "ground" :name "Int"}]
                         :output {:t "ground" :name "Keyword"}
                         :variadic false
                         :min_arity 1}]}
             (sut/type->json-data (at/->FunT tp [m]))))))

  (testing "Forall"
    (is (= {:t "forall"
            :binder ["X"]
            :body {:t "type-var" :name "X"}}
           (sut/type->json-data
            (at/->ForallT tp ['X] (at/->TypeVarT tp 'X))))))

  (testing "TypeVar"
    (is (= {:t "type-var" :name "T"}
           (sut/type->json-data (at/->TypeVarT tp 'T)))))

  (testing "SealedDyn"
    (is (= {:t "sealed" :ground {:t "ground" :name "Int"}}
           (sut/type->json-data
            (at/->SealedDynT tp (at/->GroundT tp :int 'Int))))))

  (testing "Set and Seq and Var"
    (is (= {:t "set" :members [{:t "ground" :name "Int"}]}
           (sut/type->json-data (at/->SetT tp #{(at/->GroundT tp :int 'Int)} false))))
    (is (= {:t "seq" :items [{:t "ground" :name "Int"}]}
           (sut/type->json-data (at/->SeqT tp [(at/->GroundT tp :int 'Int)] false))))
    (is (= {:t "var" :inner {:t "ground" :name "Int"}}
           (sut/type->json-data (at/->VarT tp (at/->GroundT tp :int 'Int)))))))

(deftest folded-name-returns-qualified-sym-for-foldable-source
  (doseq [source [:schema :malli :type-override]]
    (let [t (named-vector (p source 'demo/Thing))]
      (is (= 'demo/Thing (#'sut/folded-name t))))))

(deftest folded-name-nil-for-non-foldable-source
  (doseq [source [:inferred :native]]
    (let [t (named-vector (p source 'demo/x))]
      (is (nil? (#'sut/folded-name t))))))

(deftest render-type-form*-folds-root-foldable-type
  (let [schema-prov (p :schema 'demo/Thing)
        type (named-vector schema-prov)]
    (is (= 'demo/Thing (sut/render-type-form* type {})))))

(deftest render-type-form*-folds-user-schema-ground-alias
  (let [schema-prov (p :schema 'demo/IntBranch)
        type (ground-int schema-prov)]
    (is (= 'demo/IntBranch (sut/render-type-form* type {})))))

(deftest render-type-form*-does-not-fold-core-schema-ground
  (let [schema-prov (p :schema 'schema.core/Int)
        type (ground-int schema-prov)]
    (is (= 'Int (sut/render-type-form* type {})))))

(deftest render-type-form*-folds-conditional-leaf-branches
  (let [int-branch (ground-int (p :schema 'demo/IntBranch))
        str-branch (ground-str (p :schema 'demo/StrBranch))
        conditional (at/->ConditionalT tp [[integer? int-branch]
                                           [string? str-branch]])]
    (is (= '(conditional demo/IntBranch demo/StrBranch)
           (sut/render-type-form* conditional {})))
    (is (= {:t "conditional"
            :branches [{:t "named" :name "demo/IntBranch" :source "schema"}
                       {:t "named" :name "demo/StrBranch" :source "schema"}]}
           (sut/type->json-data* conditional {})))))

(deftest render-type-form*-folds-non-root-foldable-subtree
  (let [schema-prov (p :schema 'demo/Thing)
        inner (named-vector schema-prov)
        outer (at/->MapT (p :inferred 'demo/x) {(kw-key (p :inferred 'demo/x) :result) inner})]
    (is (= {:result 'demo/Thing}
           (sut/render-type-form* outer {})))))

(deftest render-type-form*-explain-full-disables-folding
  (let [schema-prov (p :schema 'demo/Thing)
        inner (named-vector schema-prov)
        outer (at/->MapT (p :inferred 'demo/x) {(kw-key (p :inferred 'demo/x) :result) inner})]
    (is (= {:result ['Int]}
           (sut/render-type-form* outer {:explain-full true})))))

(deftest type->json-data*-folds-non-root-foldable-subtree
  (let [schema-prov (p :schema 'demo/Thing)
        inner (named-vector schema-prov)
        outer (at/->MapT (p :inferred 'demo/x) {(kw-key (p :inferred 'demo/x) :result) inner})
        result (sut/type->json-data* outer {})
        first-val (-> result :entries first :val)]
    (is (= {:t "named" :name "demo/Thing" :source "schema"} first-val))))

(deftest type->json-data*-explain-full-disables-folding
  (let [schema-prov (p :schema 'demo/Thing)
        inner (named-vector schema-prov)
        outer (at/->MapT (p :inferred 'demo/x) {(kw-key (p :inferred 'demo/x) :result) inner})
        result (sut/type->json-data* outer {:explain-full true})
        first-val (-> result :entries first :val)]
    (is (= "vector" (:t first-val)))))
