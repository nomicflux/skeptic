(ns skeptic.typed-decls-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.types :as at]
            [skeptic.typed-decls :as sut]))

(defn T [schema] (ab/schema->type schema))

(deftest desc->type-fn-schema-returns-fun-type
  (let [t (sut/desc->type
            {:name "skeptic.schema.collect/raw-symbol-fn"
             :schema (s/make-fn-schema clojure.lang.Symbol
                                       [[(s/one java.lang.String 'f)]])})]
    (is (at/fun-type? t))
    (is (= 1 (count (at/fun-methods t))))
    (is (= [(T s/Str)] (at/fn-method-inputs (first (at/fun-methods t)))))
    (is (= (T s/Symbol) (at/fn-method-output (first (at/fun-methods t)))))))

(deftest desc->type-non-callable-returns-ground-type
  (let [t (sut/desc->type {:name "foo/bar" :schema s/Keyword})]
    (is (= (T s/Keyword) t))
    (is (not (at/fun-type? t)))))

(deftest typed-ns-results-dict-present-unannotated-absent
  (require 'skeptic.test-examples.basics)
  (let [{:keys [dict]} (sut/typed-ns-results {} 'skeptic.test-examples.basics)
        int-add-type (get dict 'skeptic.test-examples.basics/int-add)]
    (testing "unannotated vars are absent from dict"
      (is (not (contains? dict 'skeptic.test-examples.basics/sample-unannotated-fn))))
    (testing "annotated callable entries are FunT"
      (is (at/fun-type? int-add-type)))
    (testing "multi-arity methods are all present"
      (is (= 3 (count (at/fun-methods int-add-type)))))
    (testing "all methods have Int output"
      (is (every? #(= (T s/Int) (at/fn-method-output %)) (at/fun-methods int-add-type))))))

(deftest merge-type-dicts-passes-through-singletons-and-intersects-shared
  (let [int-t (T s/Int) str-t (T s/Str) kw-t (T s/Keyword)
        r1 {:dict {'a int-t 'shared int-t 'split int-t}
            :provenance {} :ignore-body #{} :errors []}
        r2 {:dict {'b str-t 'shared int-t 'split kw-t}
            :provenance {} :ignore-body #{} :errors []}
        merged (sut/merge-type-dicts [r1 r2])]
    (testing "symbol in only one input passes through"
      (is (= int-t (get (:dict merged) 'a)))
      (is (= str-t (get (:dict merged) 'b))))
    (testing "equal types across inputs dedup to one"
      (is (= int-t (get (:dict merged) 'shared))))
    (testing "distinct types produce IntersectionT"
      (is (at/intersection-type? (get (:dict merged) 'split))))))

(deftest typed-ns-results-omit-bad-declarations-and-keep-errors
  (require 'skeptic.best-effort-examples)
  (let [{:keys [dict errors]} (sut/typed-ns-results {} 'skeptic.best-effort-examples)]
    (is (contains? dict 'skeptic.best-effort-examples/ok-plus))
    (is (contains? dict 'skeptic.best-effort-examples/good-call))
    (is (not (contains? dict 'skeptic.best-effort-examples/invalid-schema-decl)))
    (is (= 1 (count errors)))
    (is (= :declaration (:phase (first errors))))
    (is (= 'skeptic.best-effort-examples/invalid-schema-decl
           (:blame (first errors))))))
