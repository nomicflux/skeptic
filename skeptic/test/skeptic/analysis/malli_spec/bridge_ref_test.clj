(ns skeptic.analysis.malli-spec.bridge-ref-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(deftest schema-with-local-registry-resolving-ref-to-ground
  (is (= (at/->GroundT tp :int 'Int)
         (sut/malli-spec->type
          tp
          [:schema {:registry {::x :int}} [:ref ::x]]))))

(deftest ref-resolves-inside-nested-shape
  (is (= (at/->MapT
          tp
          {(ato/exact-value-type tp :y) (at/->GroundT tp :int 'Int)
           (at/->GroundT tp :keyword 'Keyword) (at/Dyn tp)})
         (sut/malli-spec->type
          tp
          [:schema {:registry {::x :int}}
           [:map [:y [:ref ::x]]]]))))

(deftest self-recursive-ref-produces-inf-cycle-at-second-occurrence
  (is (= (at/->MaybeT
          tp
          (at/->SeqT
           tp
           [(at/->GroundT tp :int 'Int)
            (at/->InfCycleT tp ::node)]
           nil :vector))
         (sut/malli-spec->type
          tp
          [:schema {:registry {::node [:maybe [:tuple :int [:ref ::node]]]}}
           [:ref ::node]]))))

(deftest mutual-recursion-bottoms-out-at-first-repeated-ref
  (is (= (at/->MaybeT
          tp
          (at/->SeqT
           tp
           [(ato/exact-value-type tp "ping")
            (at/->MaybeT
             tp
             (at/->SeqT
              tp
              [(ato/exact-value-type tp "pong")
               (at/->InfCycleT tp ::ping)]
              nil :vector))]
           nil :vector))
         (sut/malli-spec->type
          tp
          [:schema {:registry {::ping [:maybe [:tuple [:= "ping"] [:ref ::pong]]]
                               ::pong [:maybe [:tuple [:= "pong"] [:ref ::ping]]]}}
           [:ref ::ping]]))))

(deftest plain-schema-wrapper-without-local-registry-is-transparent
  (is (= (at/->GroundT tp :int 'Int)
         (sut/malli-spec->type tp [:schema :int]))))
