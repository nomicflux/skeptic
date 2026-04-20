(ns skeptic.analysis.malli-spec.bridge-or-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(deftest or-with-two-primitive-members
  (is (= (ato/union-type [(at/->GroundT :int 'Int) (at/->GroundT :str 'Str)])
         (sut/malli-spec->type [:or :int :string]))))

(deftest or-with-single-member-short-circuits
  (is (= (ato/union-type [(at/->GroundT :int 'Int)])
         (sut/malli-spec->type [:or :int]))))

(deftest or-with-maybe-member-documents-observed-shape
  (let [expected (ato/union-type [(at/->MaybeT (at/->GroundT :int 'Int))
                                  (at/->GroundT :str 'Str)])]
    (is (= expected (sut/malli-spec->type [:or [:maybe :int] :string])))))

(deftest or-with-unknown-leaf-falls-back-to-dyn
  (let [result (sut/malli-spec->type [:or :int :uuid])]
    (is (= (ato/union-type [(at/->GroundT :int 'Int) at/Dyn])
           result))))
