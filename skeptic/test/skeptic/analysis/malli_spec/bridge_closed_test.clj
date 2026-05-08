(ns skeptic.analysis.malli-spec.bridge-closed-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(def open-domain-key (at/->GroundT tp :keyword 'Keyword))

(deftest open-default-adds-keyword-dyn-domain-entry
  (let [result (sut/malli-spec->type tp [:map [:x :int]])]
    (is (= (at/->MapT tp {(ato/exact-value-type tp :x) (at/->GroundT tp :int 'Int)
                          open-domain-key (at/Dyn tp)})
           result))))

(deftest closed-true-omits-domain-entry
  (let [result (sut/malli-spec->type tp [:map {:closed true} [:x :int]])]
    (is (= (at/->MapT tp {(ato/exact-value-type tp :x) (at/->GroundT tp :int 'Int)})
           result))))

(deftest closed-false-keeps-domain-entry
  (let [result (sut/malli-spec->type tp [:map {:closed false} [:x :int]])]
    (is (= (at/->MapT tp {(ato/exact-value-type tp :x) (at/->GroundT tp :int 'Int)
                          open-domain-key (at/Dyn tp)})
           result))))
