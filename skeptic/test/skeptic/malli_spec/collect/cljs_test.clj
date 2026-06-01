(ns skeptic.malli-spec.collect.cljs-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [skeptic.analysis.malli-spec.bridge :as amb]
            [skeptic.malli-spec.collect.cljs :as sut]
            [skeptic.worker.analyzer-cljs :as wac]))

(def ^:private fixture-path "dev-resources/cljs-fixtures/p5.cljs")

(def ^:private ^:dynamic *result* nil)

(defn- collect-once
  [f]
  (require 'malli.core)
  (let [{:keys [asts]} (wac/analyze-source-file fixture-path)
        result (sut/ns-malli-spec-results-cljs fixture-path 'p5 asts)]
    (binding [*result* result] (f))))

(use-fixtures :once collect-once)

(deftest p5-cljs-no-errors
  (is (empty? (:errors *result*))))

(deftest p5-cljs-admits-registry-declaration
  ;; `g` is declared with `(m/=> g ...)`, which the worker analyzer emits as a
  ;; `-register-function-schema!` invoke the collector reads. `h`'s
  ;; `:malli/schema` var-meta is not carried on the worker's cljs AST, so the
  ;; AST-only collector does not admit it here; end-to-end admission of the
  ;; var-meta channel is covered by the production pipeline test
  ;; `cljs-malli-registration-is-admitted-through-production-path`.
  (is (= #{'p5/g}
         (set (keys (:entries *result*))))))

(deftest p5-cljs-spec-matches-jvm
  (let [expected (amb/admit-malli-spec [:=> [:cat :int] :int])]
    (is (= expected (-> *result* :entries (get 'p5/g) :malli-spec)))))
