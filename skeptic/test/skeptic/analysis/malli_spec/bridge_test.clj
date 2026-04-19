(ns skeptic.analysis.malli-spec.bridge-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.analysis.types :as at]))

(deftest admit-malli-spec-accepts-malli-values-and-rejects-others
  (is (= :int (sut/admit-malli-spec :int)))
  (is (= [:=> [:cat :int] :int]
         (sut/admit-malli-spec [:=> [:cat :int] :int])))
  (is (thrown-with-msg? IllegalArgumentException
                        #"Expected Malli spec value"
                        (sut/admit-malli-spec (at/->GroundT :int 'Int)))))

(deftest malli-spec->type-is-dyn-stub
  (is (= at/Dyn (sut/malli-spec->type :int)))
  (is (= at/Dyn (sut/malli-spec->type [:=> [:cat :int] :int]))))
