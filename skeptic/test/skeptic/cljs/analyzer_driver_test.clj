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
        entries (:entries result)
        asts   (:asts result)]
    (is (map? result))
    (is (contains? result :ns-ast))
    (is (contains? result :entries))
    (is (contains? result :asts))
    (is (= 'p1 (:name ns-ast)))
    (is (contains? (set (vals (:requires ns-ast))) 'schema.core))
    (is (vector? entries))
    (is (vector? asts))
    (is (= (mapv :ast entries) asts))
    (is (= 1 (count asts)))
    (is (every? #(contains? % :op) asts))
    (is (= '(s/defn f :- s/Int [x :- s/Int] (+ x 1))
           (:source-form (first entries))))))

(deftest analyze-source-file-uses-cljs-reader-context
  (require 'malli.core)
  (let [{:keys [entries asts]} (sut/analyze-source-file "dev-resources/cljs-fixtures/p5.cljs")
        source-forms (mapv :source-form entries)
        names (set (keep :name asts))]
    (is (= 5 (count entries)))
    (is (not-any? #(and (seq? %) (= 'ns (first %))) source-forms))
    (is (some #(= '(def default-key :malli.core/default) %) source-forms))
    (is (some #(and (seq? %)
                    (= 'def (first %))
                    (= 'js-value (second %)))
              source-forms))
    (is (contains? names 'p5/default-key))
    (is (contains? names 'p5/js-value))))
