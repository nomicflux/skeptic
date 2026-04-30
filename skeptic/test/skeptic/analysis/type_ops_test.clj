(ns skeptic.analysis.type-ops-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.provenance :as prov]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.type-algebra :as ata]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest tagged-polymorphic-type-helpers-test
  (let [type-var (at/->TypeVarT tp 'X)
        forall (at/->ForallT tp 'X (at/->FunT tp [(at/->FnMethodT tp [type-var]
                                                           type-var
                                                           1
                                                           false
                                                           '[x])]))
        sealed (at/->SealedDynT tp type-var)]
    (is (at/type-var-type? type-var))
    (is (at/forall-type? forall))
    (is (at/sealed-dyn-type? sealed))
    (is (= #{'Y}
           (ata/type-free-vars (at/->ForallT tp 'X
                                             (at/->FunT tp [(at/->FnMethodT tp [type-var]
                                                                        (at/->TypeVarT tp 'Y)
                                                                        1
                                                                        false
                                                                        '[x])])))))
    (is (at/type=? (at/->ForallT tp 'X (at/->TypeVarT tp 'X))
           (ata/type-substitute (at/->ForallT tp 'X (at/->TypeVarT tp 'X))
                                'X
                                (ab/schema->type tp s/Any))))))

(deftest semantic-function-type-rendering-test
  (let [fun-type (at/->FunT tp [(at/->FnMethodT tp [(ab/schema->type tp s/Int)]
                                             (ato/intersection-type tp [(ab/schema->type tp s/Any)
                                                                     (ab/schema->type tp s/Int)])
                                             1
                                             false
                                             '[x])])
        polymorphic-fun (at/->FunT tp [(at/->FnMethodT tp [(at/->TypeVarT tp 'X)]
                                                    (at/->SealedDynT tp (at/->TypeVarT tp 'X))
                                                    1
                                                    false
                                                    '[x])])
        inf-cycle (at/->InfCycleT tp 'example/self)]
    (is (= fun-type (ato/normalize-type tp fun-type)))
    (is (= "(=> (intersection Any Int) Int)"
           (abr/render-type fun-type)))
    (is (= "(=> (sealed X) X)"
           (abr/render-type polymorphic-fun)))
    (is (= "(InfCycle example/self)"
           (abr/render-type inf-cycle)))))

(deftest type-ops-normalization-and-unknown-test
  (is (at/type=? (at/->ValueT tp (at/->GroundT tp :keyword 'Keyword) :k)
         (ato/exact-value-type tp :k)))
  (is (at/type=? (at/->ValueT tp (at/->GroundT tp {:class java.lang.Double} 'Double) 3.5)
         (ato/exact-value-type tp 3.5)))
  (is (at/type=? (at/->MaybeT tp (at/Dyn tp))
         (ato/normalize-type tp nil)))
  (is (at/type=? (at/->GroundT tp :int 'Int)
         (ato/de-maybe-type tp (at/->MaybeT tp (at/->GroundT tp :int 'Int)))))
  (is (ato/uninformative-for-narrowing? (at/Dyn tp)))
  (is (ato/uninformative-for-narrowing? (at/->PlaceholderT tp 'example/x)))
  (is (ato/uninformative-for-narrowing? (at/->InfCycleT tp 'example/self)))
  (is (not (ato/uninformative-for-narrowing? (ab/schema->type tp s/Int)))))

(deftest strict-normalize-type-contract-test
  (let [semantic-map (at/->MapT tp {(at/->GroundT tp :keyword 'Keyword)
                                 (at/->GroundT tp :int 'Int)})]
    (is (= semantic-map
           (ato/normalize-type tp semantic-map))))
  (is (thrown-with-msg? IllegalArgumentException
                        #"normalize-type only accepts canonical semantic types or internal type-like values"
                        (ato/normalize-type tp s/Int))))

(deftest normalize-type-preserves-conditional-test
  (let [pred1 :map?
        pred2 :vector?
        cond-type (at/->ConditionalT tp [[pred1 (ab/schema->type tp s/Int) nil]
                                         [pred2 (ab/schema->type tp s/Str) nil]])]
    (is (at/conditional-type? (ato/normalize-type tp cond-type)))
    (is (at/type=? cond-type (ato/normalize-type tp cond-type)))))

(deftest de-maybe-type-on-conditional-test
  (let [pred1 :map?
        pred2 :vector?
        int-t (ab/schema->type tp s/Int)
        str-t (ab/schema->type tp s/Str)
        cond-type (at/->ConditionalT tp [[pred1 (at/->MaybeT tp int-t) nil]
                                         [pred2 str-t nil]])
        result (ato/de-maybe-type tp cond-type)]
    (is (at/conditional-type? result))
    (is (= 2 (count (:branches result))))
    (is (at/type=? int-t (second (first (:branches result)))))
    (is (at/type=? str-t (second (second (:branches result)))))))

(deftest de-maybe-type-on-maybe-conditional-test
  (let [pred1 :map?
        int-t (ab/schema->type tp s/Int)
        cond-type (at/->ConditionalT tp [[pred1 int-t nil]])
        wrapped (at/->MaybeT tp cond-type)
        result (ato/de-maybe-type tp wrapped)]
    (is (at/conditional-type? result))
    (is (at/type=? cond-type result))))

(deftest unknown-type-on-conditional-test
  (let [pred1 :map?
        pred2 :vector?
        int-t (ab/schema->type tp s/Int)
        str-t (ab/schema->type tp s/Str)
        all-concrete (at/->ConditionalT tp [[pred1 int-t nil] [pred2 str-t nil]])
        with-dyn (at/->ConditionalT tp [[pred1 int-t nil] [pred2 (at/Dyn tp) nil]])]
    (is (not (ato/uninformative-for-narrowing? all-concrete)))
    (is (ato/uninformative-for-narrowing? with-dyn))))

(deftest union-with-conditional-member-preserves-nilability-test
  (let [pred1 :map?
        pred2 :vector?
        int-t (ab/schema->type tp s/Int)
        cond-with-maybe (at/->ConditionalT tp [[pred1 (at/->MaybeT tp int-t) nil]
                                               [pred2 int-t nil]])
        result (ato/union [cond-with-maybe int-t])]
    (is (at/maybe-type? result))))
