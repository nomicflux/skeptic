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

(defn- kw-key
  [prov k]
  (at/->ValueT prov (at/->GroundT prov :keyword 'Keyword) k))

(defn- named-map
  [prov]
  (at/->MapT prov {(kw-key prov :result) (ground-int prov)}))

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

(deftest build-fold-index-deterministic-selection
  (let [schema-prov (p :schema 'zeta/Thing)
        override-prov (p :type-override 'alpha/Thing)
        schema-type (named-map schema-prov)
        override-type (named-map override-prov)
        idx (sut/build-fold-index {'zeta/Thing schema-type
                                   'alpha/Thing override-type}
                                  {'zeta/Thing schema-prov
                                   'alpha/Thing override-prov})]
    (is (= 'alpha/Thing
           (:qualified-sym (sut/folded-entry idx schema-type))))
    (is (= :type-override
           (:source (sut/folded-entry idx schema-type)))))

  (let [malli-a (p :malli-spec 'alpha/Thing)
        malli-z (p :malli-spec 'zeta/Thing)
        idx (sut/build-fold-index {'zeta/Thing (named-map malli-z)
                                   'alpha/Thing (named-map malli-a)}
                                  {'zeta/Thing malli-z
                                   'alpha/Thing malli-a})]
    (is (= 'alpha/Thing
           (:qualified-sym (sut/folded-entry idx (named-map tp))))))

  (let [native-prov (p :native 'native/fn)
        idx (sut/build-fold-index {'native/fn (named-map native-prov)}
                                  {'native/fn native-prov})]
    (is (empty? idx))))

(deftest opts-aware-render-and-json-folding
  (let [schema-prov (p :schema 'demo/ThreeColour)
        inferred-prov (p :inferred 'demo/caller)
        fold-index (sut/build-fold-index {'demo/ThreeColour (named-vector schema-prov)}
                                         {'demo/ThreeColour schema-prov})
        nested-map (at/->MapT inferred-prov {(kw-key inferred-prov :result)
                                             (named-vector inferred-prov)})
        named-union (at/->UnionT inferred-prov
                                 #{(named-vector inferred-prov)
                                   (at/->MapT inferred-prov {(kw-key inferred-prov :x)
                                                             (ground-int inferred-prov)})})]
    (is (= {:result 'demo/ThreeColour}
           (sut/render-type-form* nested-map {:fold-index fold-index})))
    (is (= ['Int]
           (sut/render-type-form* (named-vector inferred-prov)
                                  {:fold-index fold-index
                                   :explain-full true})))
    (is (= {:t "named"
            :name "demo/ThreeColour"
            :source "schema"}
           (sut/type->json-data* (named-vector inferred-prov)
                                 {:fold-index fold-index})))
    (is (= {:t "vector"
            :items [{:t "ground" :name "Int"}]}
           (sut/type->json-data* (named-vector inferred-prov)
                                 {:fold-index fold-index
                                  :explain-full true})))
    (let [rendered (sut/render-type-form* named-union {:fold-index fold-index})]
      (is (= 'union (first rendered)))
      (is (= #{'demo/ThreeColour '{:x Int}}
             (set (rest rendered)))))
    (let [members (:members (sut/type->json-data* named-union {:fold-index fold-index}))]
      (is (= "union" (:t (sut/type->json-data* named-union {:fold-index fold-index}))))
      (is (= #{{:t "named" :name "demo/ThreeColour" :source "schema"}
               {:t "map"
                :entries [{:key {:t "value" :value ":x"}
                           :val {:t "ground" :name "Int"}}]}}
             (set members))))))
