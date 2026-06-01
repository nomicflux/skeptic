(ns skeptic.cljs.analyzer-driver-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.cljs.analyzer-driver :as sut]
            [skeptic.worker.analyzer-cljs :as wac]))

(deftest analyze-form-handles-aliased-schema-defn
  (require 'schema.core)
  (let [ns-ast (:ns-ast (wac/analyze-source-file "dev-resources/cljs-fixtures/p4.cljs"))
        ast (sut/analyze-form ns-ast
                              '(s/defn g :- s/Int [x :- s/Int] (+ x 1)))]
    (is (= :let (:op ast)))
    (is (= 5 (count (:bindings ast))))))
