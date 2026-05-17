(ns skeptic.analysis.malli-spec.bridge-sequential-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(deftest sequential-of-int-imports-as-empty-prefix-int-tail-seqt
  (is (= (at/->SeqT tp (at/pattern-from-prefix-tail [] (at/->GroundT tp :int 'Int)) :sequential)
         (sut/malli-spec->type tp [:sequential :int]))))

(deftest sequential-with-properties-drops-them
  (is (= (at/->SeqT tp (at/pattern-from-prefix-tail [] (at/->GroundT tp :int 'Int)) :sequential)
         (sut/malli-spec->type tp [:sequential {:min 1} :int]))))
