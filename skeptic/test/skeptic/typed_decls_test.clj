(ns skeptic.typed-decls-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.typed-decls :as sut]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest desc->typed-entry-converts-callable-entries-to-semantic-types
  (let [typed-entry (sut/desc->typed-entry
                     {:name "skeptic.schema.collect/raw-symbol-fn"
                      :schema (s/make-fn-schema clojure.lang.Symbol
                                                [[(s/one java.lang.String 'f)]])
                      :output clojure.lang.Symbol
                      :arglists {1 {:arglist '[f]
                                    :count 1
                                    :schema [{:schema java.lang.String
                                              :optional? false
                                              :name 'f}]}}})]
    (is (= "skeptic.schema.collect/raw-symbol-fn" (:name typed-entry)))
    (is (= [(T (s/make-fn-schema s/Symbol [[(s/one s/Str 'f)]]))]
           (:typings typed-entry)))
    (is (= (T s/Symbol) (:output-type typed-entry)))
    (is (= {1 {:arglist '[f]
               :count 1
               :types [{:name 'f
                        :optional? false
                        :type (T s/Str)}]}}
           (:arglists typed-entry)))
    (is (not (contains? typed-entry :schema)))
    (is (not (contains? typed-entry :output)))))

(deftest desc->typed-entry-omits-callable-only-fields-for-non-callables
  (let [typed-entry (sut/desc->typed-entry
                     {:name "skeptic.schema.collect/raw-keyword"
                      :schema s/Keyword})]
    (is (= {:name "skeptic.schema.collect/raw-keyword"
            :typings [(T s/Keyword)]}
           typed-entry))
    (is (not (contains? typed-entry :output-type)))
    (is (not (contains? typed-entry :arglists)))))

(deftest typed-ns-entries-annotated-present-unannotated-absent
  (require 'skeptic.test-examples.basics)
  (let [entries (sut/typed-ns-entries {} 'skeptic.test-examples.basics)
        int-add (get entries 'skeptic.test-examples.basics/int-add)]
    (testing "unannotated vars are absent from typed-ns-entries"
      (is (not (contains? entries 'skeptic.test-examples.basics/sample-unannotated-fn))))
    (testing "annotated callable entries are semantic only"
      (is (= (T s/Int) (:output-type int-add)))
      (is (not (contains? int-add :schema)))
      (is (not (contains? int-add :output))))
    (testing "varargs arities preserve arglist shape, count, and semantic arg types"
      (is (= ['x 'y ['zs]]
             (get-in int-add [:arglists :varargs :arglist])))
      (is (= 3
             (get-in int-add [:arglists :varargs :count])))
      (is (= [{:name 'x :optional? false :type (T s/Int)}
              {:name 'y :optional? false :type (T s/Int)}
              {:name 'zs :optional? false :type (T s/Int)}]
             (get-in int-add [:arglists :varargs :types]))))))

(deftest merge-typed-dicts-passes-through-singletons-dedups-and-concatenates
  (let [int-t (T s/Int) str-t (T s/Str) kw-t (T s/Keyword)
        d1 {'a {:name 'a :typings [int-t] :arglists {1 :a-arglists}}
            'shared {:name 'shared :typings [int-t] :output-type int-t}
            'split {:name 'split :typings [int-t]}}
        d2 {'b {:name 'b :typings [str-t]}
            'shared {:name 'shared :typings [int-t] :output-type str-t}
            'split {:name 'split :typings [kw-t]}}
        merged (sut/merge-typed-dicts [d1 d2])]
    (testing "symbol in only one input passes through"
      (is (= (get d1 'a) (get merged 'a)))
      (is (= (get d2 'b) (get merged 'b))))
    (testing "equal typings across inputs dedup to one typing"
      (is (= [int-t] (:typings (get merged 'shared)))))
    (testing "distinct typings concatenate"
      (is (= [int-t kw-t] (:typings (get merged 'split)))))
    (testing "non-typing fields come from the first input that had the symbol"
      (is (= int-t (:output-type (get merged 'shared)))))))

(deftest typed-ns-results-omit-bad-declarations-and-keep-errors
  (require 'skeptic.best-effort-examples)
  (let [{:keys [entries errors]} (sut/typed-ns-results {} 'skeptic.best-effort-examples)]
    (is (contains? entries 'skeptic.best-effort-examples/ok-plus))
    (is (contains? entries 'skeptic.best-effort-examples/good-call))
    (is (not (contains? entries 'skeptic.best-effort-examples/invalid-schema-decl)))
    (is (= 1 (count errors)))
    (is (= :declaration (:phase (first errors))))
    (is (= 'skeptic.best-effort-examples/invalid-schema-decl
           (:blame (first errors))))))

