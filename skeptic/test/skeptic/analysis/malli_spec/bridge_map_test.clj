(ns skeptic.analysis.malli-spec.bridge-map-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(defn- exact-keyword-type [k]
  (ato/exact-value-type tp k))

(deftest map-with-required-keys
  (is (= (at/->MapT tp {(exact-keyword-type :x) (at/->GroundT tp :int 'Int)
                        (exact-keyword-type :y) (at/->GroundT tp :str 'Str)})
         (sut/malli-spec->type tp [:map [:x :int] [:y :string]]))))

(deftest map-with-optional-key
  (is (= (at/->MapT tp {(exact-keyword-type :x) (at/->GroundT tp :int 'Int)
                        (at/->OptionalKeyT tp (exact-keyword-type :y)) (at/->GroundT tp :str 'Str)})
         (sut/malli-spec->type tp [:map [:x :int] [:y {:optional true} :string]]))))

(deftest map-with-empty-props-treats-key-as-required
  (is (= (at/->MapT tp {(exact-keyword-type :x) (at/->GroundT tp :int 'Int)})
         (sut/malli-spec->type tp [:map [:x {} :int]]))))

(deftest map-with-closed-prop-is-ignored
  (is (= (at/->MapT tp {(exact-keyword-type :x) (at/->GroundT tp :int 'Int)})
         (sut/malli-spec->type tp [:map {:closed true} [:x :int]]))))

(deftest map-with-maybe-value
  (is (= (at/->MapT tp {(exact-keyword-type :x) (at/->MaybeT tp (at/->GroundT tp :int 'Int))})
         (sut/malli-spec->type tp [:map [:x [:maybe :int]]]))))

(deftest map-with-or-value
  (is (= (at/->MapT tp {(exact-keyword-type :x)
                        (ato/union-type tp [(at/->GroundT tp :int 'Int)
                                            (at/->GroundT tp :str 'Str)])})
         (sut/malli-spec->type tp [:map [:x [:or :int :string]]]))))

(deftest map-with-nested-map-value
  (is (= (at/->MapT tp {(exact-keyword-type :outer)
                        (at/->MapT tp {(exact-keyword-type :inner) (at/->GroundT tp :int 'Int)})})
         (sut/malli-spec->type tp [:map [:outer [:map [:inner :int]]]]))))

(deftest bare-map-keyword-falls-back-to-dyn
  (is (= (at/Dyn tp) (sut/malli-spec->type tp [:map]))))
