(ns skeptic.malli-spec.collect.cljs-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.analysis.types :as at]
            [skeptic.cljs.analyzer-driver :as driver]
            [skeptic.malli-spec.collect.cljs :as sut]
            [skeptic.typed-decls.malli :as typed-malli]))

(def ^:private fixture-path "dev-resources/cljs-fixtures/p5.cljs")

(def ^:private ^:dynamic *result* nil)

(defn- collect-once
  [f]
  (require 'malli.core)
  (let [{:keys [asts]} (driver/analyze-source-file fixture-path)
        result (sut/ns-malli-spec-results-cljs fixture-path 'p5 asts)]
    (binding [*result* result] (f))))

(use-fixtures :once collect-once)

(deftest p5-cljs-no-errors
  (is (empty? (:errors *result*))))

(deftest p5-cljs-admits-both
  (is (= #{'p5/g 'p5/h}
         (set (keys (:entries *result*))))))

(deftest p5-cljs-spec-matches-jvm
  (let [expected (amb/admit-malli-spec [:=> [:cat :int] :int])]
    (is (= expected (-> *result* :entries (get 'p5/g) :malli-spec)))
    (is (= expected (-> *result* :entries (get 'p5/h) :malli-spec)))))

(deftest unsupported-cljs-spec-preserves-raw-spec-and-converts-to-dyn-at-consumer
  (let [marker (sut/unsupported-cljs-spec :any (IllegalArgumentException. "not admitted"))
        result (typed-malli/convert-collected
                'p5
                :cljs
                nil
                {:entries {'p5/unknown {:name "p5/unknown" :malli-spec marker}}
                 :errors []})]
    (is (= :any (get marker sut/unsupported-cljs-spec-key)))
    (is (empty? (:errors result)))
    (is (at/dyn-type? (get-in result [:dict 'p5/unknown])))))
