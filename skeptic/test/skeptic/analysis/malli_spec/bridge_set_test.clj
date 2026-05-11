(ns skeptic.analysis.malli-spec.bridge-set-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(deftest set-of-int-imports-as-homogeneous-singleton-set
  (is (= (at/->SetT tp #{(at/->GroundT tp :int 'Int)} true)
         (sut/malli-spec->type tp [:set :int]))))

(deftest set-with-properties-drops-them
  (is (= (at/->SetT tp #{(at/->GroundT tp :int 'Int)} true)
         (sut/malli-spec->type tp [:set {:min 1} :int]))))
