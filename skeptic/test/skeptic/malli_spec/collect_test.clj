(ns skeptic.malli-spec.collect-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [skeptic.malli-spec.collect :as sut]))

(defn ^{:malli/schema [:=> [:cat :int] :int]} demo-fn [x] x)

(defn plain-fn [x] x)

(deftest discovers-vars-with-malli-schema-metadata
  (let [result (sut/ns-malli-spec-results {} 'skeptic.malli-spec.collect-test)]
    (is (= [] (:errors result)))
    (is (contains? (:entries result) 'skeptic.malli-spec.collect-test/demo-fn))
    (is (= {:name "skeptic.malli-spec.collect-test/demo-fn"
            :malli-spec (m/form (m/schema [:=> [:cat :int] :int]))}
           (get (:entries result) 'skeptic.malli-spec.collect-test/demo-fn)))))

(deftest skips-vars-without-malli-schema-metadata
  (let [result (sut/ns-malli-spec-results {} 'skeptic.malli-spec.collect-test)]
    (is (not (contains? (:entries result) 'skeptic.malli-spec.collect-test/plain-fn)))))

(deftest ns-malli-specs-returns-just-entries
  (let [result (sut/ns-malli-spec-results {} 'skeptic.malli-spec.collect-test)]
    (is (= (:entries result)
           (sut/ns-malli-specs {} 'skeptic.malli-spec.collect-test)))))
