(ns skeptic.malli-spec.collect.cljs-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.cljs.compiler-env :as compiler-env]
            [skeptic.malli-spec.collect.cljs :as sut]))

(def ^:private fixture-path "dev-resources/cljs-fixtures/p5.cljs")

(def ^:private ^:dynamic *result* nil)

(defn- collect-once
  [f]
  (require 'malli.core)
  (let [cenv (compiler-env/fresh-state)
        asts (compiler-env/load-source! cenv fixture-path)
        result (sut/ns-malli-spec-results-cljs cenv fixture-path 'p5 asts)]
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
