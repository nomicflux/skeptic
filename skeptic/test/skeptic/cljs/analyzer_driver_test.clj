(ns skeptic.cljs.analyzer-driver-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.cljs.analyzer-driver :as sut]))

(deftest analyze-form-handles-aliased-schema-defn
  (require 'schema.core)
  (let [ns-ast (sut/parse-source-ns "dev-resources/cljs-fixtures/p4.cljs")
        ast (sut/analyze-form ns-ast
                              '(s/defn g :- s/Int [x :- s/Int] (+ x 1)))]
    (is (= :let (:op ast)))
    (is (= 5 (count (:bindings ast))))))

(deftest analyze-source-file-returns-ns-ast-and-asts
  (require 'schema.core)
  (let [result (sut/analyze-source-file "dev-resources/cljs-fixtures/p1.cljs")
        ns-ast (:ns-ast result)
        asts   (:asts result)]
    (is (map? result))
    (is (contains? result :ns-ast))
    (is (contains? result :asts))
    (is (= 'p1 (:name ns-ast)))
    (is (contains? (set (vals (:requires ns-ast))) 'schema.core))
    (is (vector? asts))
    (is (= 1 (count asts)))
    (is (every? #(contains? % :op) asts))))
