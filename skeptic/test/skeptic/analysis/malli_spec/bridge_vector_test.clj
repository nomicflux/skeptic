(ns skeptic.analysis.malli-spec.bridge-vector-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(deftest vector-of-int-imports-as-empty-prefix-int-tail
  (is (= (at/->VectorT tp [] (at/->GroundT tp :int 'Int))
         (sut/malli-spec->type tp [:vector :int]))))

(deftest vector-with-properties-drops-them
  (is (= (at/->VectorT tp [] (at/->GroundT tp :int 'Int))
         (sut/malli-spec->type tp [:vector {:min 1 :max 10} :int]))))

(deftest vector-of-maybe-int-imports-with-maybe-tail
  (is (= (at/->VectorT tp [] (at/->MaybeT tp (at/->GroundT tp :int 'Int)))
         (sut/malli-spec->type tp [:vector [:maybe :int]]))))
