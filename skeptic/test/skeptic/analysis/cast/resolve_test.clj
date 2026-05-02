(ns skeptic.analysis.cast.resolve-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as sut]
            [skeptic.analysis.cast.result :as result]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(s/defschema X-bound s/Int)
(s/defschema X-self (s/recursive #'X-self))

(def tp (prov/make-provenance :inferred 'test-sym 'skeptic.analysis.cast.resolve-test nil))

(defn T [schema] (ab/schema->type tp schema))

(deftest target-placeholder-bound
  (let [target (at/->PlaceholderT tp 'skeptic.analysis.cast.resolve-test/X-bound)
        source (T s/Int)]
    (is (result/ok? (sut/check-cast source target)))))

(deftest target-infcycle-self
  (let [target (at/->InfCycleT tp 'skeptic.analysis.cast.resolve-test/X-self)
        source (T s/Int)
        r (sut/check-cast source target)]
    (is (result/ok? r))
    (is (= :residual-dynamic (:rule r)))))

(deftest target-placeholder-unbound
  (let [target (at/->PlaceholderT tp 'skeptic.analysis.cast.resolve-test/undefined-sym-no-such-var)
        source (T s/Int)
        r (sut/check-cast source target)]
    (is (result/ok? r))
    (is (= :residual-dynamic (:rule r)))))
