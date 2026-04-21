(ns skeptic.analysis.bridge.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.bridge.render :as sut]
            [skeptic.analysis.types :as at]))

(deftest type->json-data-scalars
  (testing "nil passthrough"
    (is (nil? (sut/type->json-data nil))))

  (testing "Dyn and Bottom"
    (is (= {:t "any"} (sut/type->json-data (at/->DynT))))
    (is (= {:t "bottom"} (sut/type->json-data (at/->BottomT)))))

  (testing "Ground"
    (is (= {:t "ground" :name "Int"}
           (sut/type->json-data (at/->GroundT :int 'Int)))))

  (testing "Value"
    (is (= {:t "value" :value ":k"}
           (sut/type->json-data
            (at/->ValueT (at/->GroundT :keyword 'Keyword) :k))))))

(deftest type->json-data-constructors
  (testing "Maybe"
    (is (= {:t "maybe" :inner {:t "ground" :name "Int"}}
           (sut/type->json-data (at/->MaybeT (at/->GroundT :int 'Int))))))

  (testing "Conditional"
    (let [conditional (at/->ConditionalT [[integer? (at/->GroundT :int 'Int)]
                                          [string? (at/->MaybeT (at/->GroundT :keyword 'Keyword))]])]
      (is (= '(conditional Int (maybe Keyword))
             (sut/render-type-form conditional)))
      (is (= {:t "conditional"
              :branches [{:t "ground" :name "Int"}
                         {:t "maybe"
                          :inner {:t "ground" :name "Keyword"}}]}
             (sut/type->json-data conditional)))))

  (testing "Union sorts members for stability"
    (let [result (sut/type->json-data
                  (at/->UnionT #{(at/->GroundT :int 'Int)
                                 (at/->GroundT :keyword 'Keyword)}))]
      (is (= "union" (:t result)))
      (is (= [{:t "ground" :name "Int"} {:t "ground" :name "Keyword"}]
             (:members result)))))

  (testing "Vector"
    (is (= {:t "vector" :items [{:t "ground" :name "Int"}]}
           (sut/type->json-data (at/->VectorT [(at/->GroundT :int 'Int)] false)))))

  (testing "Map"
    (is (= {:t "map"
            :entries [{:key {:t "ground" :name "Keyword"}
                       :val {:t "ground" :name "Int"}}]}
           (sut/type->json-data
            (at/->MapT {(at/->GroundT :keyword 'Keyword)
                        (at/->GroundT :int 'Int)})))))

  (testing "OptionalKey"
    (is (= {:t "optional-key" :inner {:t "ground" :name "Int"}}
           (sut/type->json-data (at/->OptionalKeyT (at/->GroundT :int 'Int))))))

  (testing "Fun and FnMethod"
    (let [m (at/->FnMethodT [(at/->GroundT :int 'Int)]
                            (at/->GroundT :keyword 'Keyword)
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
             (sut/type->json-data (at/->FunT [m]))))))

  (testing "Forall"
    (is (= {:t "forall"
            :binder ["X"]
            :body {:t "type-var" :name "X"}}
           (sut/type->json-data
            (at/->ForallT ['X] (at/->TypeVarT 'X))))))

  (testing "TypeVar"
    (is (= {:t "type-var" :name "T"}
           (sut/type->json-data (at/->TypeVarT 'T)))))

  (testing "SealedDyn"
    (is (= {:t "sealed" :ground {:t "ground" :name "Int"}}
           (sut/type->json-data
            (at/->SealedDynT (at/->GroundT :int 'Int))))))

  (testing "Set and Seq and Var"
    (is (= {:t "set" :members [{:t "ground" :name "Int"}]}
           (sut/type->json-data (at/->SetT #{(at/->GroundT :int 'Int)} false))))
    (is (= {:t "seq" :items [{:t "ground" :name "Int"}]}
           (sut/type->json-data (at/->SeqT [(at/->GroundT :int 'Int)] false))))
    (is (= {:t "var" :inner {:t "ground" :name "Int"}}
           (sut/type->json-data (at/->VarT (at/->GroundT :int 'Int)))))))
