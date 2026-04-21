(ns skeptic.analysis.type-ops-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.type-algebra :as ata]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(deftest tagged-polymorphic-type-helpers-test
  (let [type-var (at/->TypeVarT 'X)
        forall (at/->ForallT 'X (at/->FunT [(at/->FnMethodT [type-var]
                                                           type-var
                                                           1
                                                           false
                                                           '[x])]))
        sealed (at/->SealedDynT type-var)]
    (is (at/type-var-type? type-var))
    (is (at/forall-type? forall))
    (is (at/sealed-dyn-type? sealed))
    (is (= #{'Y}
           (ata/type-free-vars (at/->ForallT 'X
                                             (at/->FunT [(at/->FnMethodT [type-var]
                                                                        (at/->TypeVarT 'Y)
                                                                        1
                                                                        false
                                                                        '[x])])))))
    (is (= (at/->ForallT 'X (at/->TypeVarT 'X))
           (ata/type-substitute (at/->ForallT 'X (at/->TypeVarT 'X))
                                'X
                                (ab/schema->type s/Any))))))

(deftest semantic-function-type-rendering-test
  (let [fun-type (at/->FunT [(at/->FnMethodT [(ab/schema->type s/Int)]
                                             (ato/intersection-type [(ab/schema->type s/Any)
                                                                     (ab/schema->type s/Int)])
                                             1
                                             false
                                             '[x])])
        polymorphic-fun (at/->FunT [(at/->FnMethodT [(at/->TypeVarT 'X)]
                                                    (at/->SealedDynT (at/->TypeVarT 'X))
                                                    1
                                                    false
                                                    '[x])])
        inf-cycle (at/->InfCycleT 'example/self)]
    (is (= fun-type (ato/normalize-type fun-type)))
    (is (= "(=> (intersection Any Int) Int)"
           (abr/render-type fun-type)))
    (is (= "(=> (sealed X) X)"
           (abr/render-type polymorphic-fun)))
    (is (= "(InfCycle example/self)"
           (abr/render-type inf-cycle)))))

(deftest type-ops-normalization-and-unknown-test
  (is (= (at/->ValueT (at/->GroundT :keyword 'Keyword) :k)
         (ato/exact-value-type :k)))
  (is (= (at/->ValueT (at/->GroundT {:class java.lang.Double} 'Double) 3.5)
         (ato/exact-value-type 3.5)))
  (is (= (at/->MaybeT at/Dyn)
         (ato/normalize-type nil)))
  (is (= (at/->GroundT :int 'Int)
         (ato/de-maybe-type (at/->MaybeT (at/->GroundT :int 'Int)))))
  (is (ato/unknown-type? at/Dyn))
  (is (ato/unknown-type? (at/->PlaceholderT 'example/x)))
  (is (ato/unknown-type? (at/->InfCycleT 'example/self)))
  (is (not (ato/unknown-type? (ab/schema->type s/Int)))))

(deftest semantic-type-tag-validation-test
  (is (not (at/semantic-type-value? {at/semantic-type-tag-key :not-a-real-semantic-type})))
  (is (= at/ground-type-tag
         (at/semantic-type-tag (at/->GroundT :int 'Int))))
  (is (at/known-semantic-type-tag? at/ground-type-tag)))

(deftest strict-normalize-type-contract-test
  (let [semantic-map (at/->MapT {(at/->GroundT :keyword 'Keyword)
                                 (at/->GroundT :int 'Int)})]
    (is (= semantic-map
           (ato/normalize-type semantic-map))))
  (is (thrown-with-msg? IllegalArgumentException
                        #"normalize-type only accepts canonical semantic types or internal type-like values"
                        (ato/normalize-type s/Int))))
