(ns skeptic.schematize-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.schematize :as sut]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest arg-list-only-varargs
  (is (= {:count 2, :args '[x y], :with-varargs false, :varargs []}
         (sut/arg-list '[x y])))
  (is (= {:count 1, :args [], :with-varargs true, :varargs '[rest]}
         (sut/arg-list '[& rest])))
  (is (= {:count 2, :args '[x], :with-varargs true, :varargs '[rest]}
         (sut/arg-list '[x & rest]))))

(deftest ns-schemas-reads-auto-resolved-keywords-in-target-ns
  (require 'skeptic.test-examples)
  (is (contains? (sut/ns-schemas {} 'skeptic.test-examples)
                 'skeptic.test-examples/sample-namespaced-keyword-fn)))

(deftest ns-schemas-reads-auto-resolved-keywords-in-source-namespaces
  (require 'skeptic.inconsistence.report)
  (is (map? (sut/ns-schemas {} 'skeptic.inconsistence.report))))

(deftest collect-schemas-canonicalizes-schema-representations
  (let [symbol-desc (sut/collect-schemas {:schema (s/make-fn-schema clojure.lang.Symbol
                                                                   [[(s/one java.lang.String 'f)]])
                                          :ns 'skeptic.schematize
                                          :name 'raw-symbol-fn
                                          :arglists '([f])})
        keyword-desc (sut/collect-schemas {:schema (s/make-fn-schema clojure.lang.Keyword
                                                                    [[(s/one java.lang.Integer 'x)]])
                                           :ns 'skeptic.schematize
                                           :name 'raw-keyword-fn
                                           :arglists '([x])})]
    (is (= s/Symbol (:output symbol-desc)))
    (is (= s/Str (get-in symbol-desc [:arglists 1 :schema 0 :schema])))
    (is (= s/Keyword (:output keyword-desc)))
    (is (= s/Int (get-in keyword-desc [:arglists 1 :schema 0 :schema])))))

(deftest ns-schemas-canonicalizes-known-public-schemas
  (require 'skeptic.schematize)
  (let [schemas (sut/ns-schemas {} 'skeptic.schematize)]
    (is (= s/Symbol
           (get-in schemas ['skeptic.schematize/fully-qualify-str :output])))
    (is (= s/Str
           (get-in schemas ['skeptic.schematize/fully-qualify-str :arglists 1 :schema 0 :schema])))))

(deftest collect-schemas-rejects-invalid-schema-annotations-early
  (is (thrown-with-msg? IllegalArgumentException
                        #"Invalid Schema annotation"
                        (sut/collect-schemas {:schema [(Object.)]
                                              :ns 'skeptic.schematize
                                              :name 'invalid
                                              :arglists '([])}))))

(deftest schema-desc->typed-entry-converts-callable-entries-to-semantic-types
  (let [typed-entry (sut/schema-desc->typed-entry
                     (sut/collect-schemas {:schema (s/make-fn-schema clojure.lang.Symbol
                                                                   [[(s/one java.lang.String 'f)]])
                                           :ns 'skeptic.schematize
                                           :name 'raw-symbol-fn
                                           :arglists '([f])}))]
    (is (= "skeptic.schematize/raw-symbol-fn" (:name typed-entry)))
    (is (= (T (s/make-fn-schema s/Symbol [[(s/one s/Str 'f)]]))
           (:type typed-entry)))
    (is (= (T s/Symbol) (:output-type typed-entry)))
    (is (= {1 {:arglist '[f]
               :count 1
               :types [{:name 'f
                        :optional? false
                        :type (T s/Str)}]}}
           (:arglists typed-entry)))
    (is (not (contains? typed-entry :schema)))
    (is (not (contains? typed-entry :output)))))

(deftest schema-desc->typed-entry-omits-callable-only-fields-for-non-callables
  (let [typed-entry (sut/schema-desc->typed-entry
                     {:name "skeptic.schematize/raw-keyword"
                      :schema s/Keyword})]
    (is (= {:name "skeptic.schematize/raw-keyword"
            :type (T s/Keyword)}
           typed-entry))
    (is (not (contains? typed-entry :output-type)))
    (is (not (contains? typed-entry :arglists)))))

(deftest typed-ns-schemas-builds-semantic-callable-dynamic-and-varargs-entries
  (require 'skeptic.test-examples)
  (let [schemas (sut/typed-ns-schemas {} 'skeptic.test-examples)
        int-add (get schemas 'skeptic.test-examples/int-add)
        sample-fn (get schemas 'skeptic.test-examples/sample-fn)]
    (testing "callable entries are semantic only"
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
             (get-in int-add [:arglists :varargs :types]))))
    (testing "dynamic fallback entries become fully typed Any entries"
      (is (= (T s/Any) (:type sample-fn)))
      (is (= (T s/Any) (:output-type sample-fn)))
      (is (= [{:name 'x :optional? false :type (T s/Any)}]
             (get-in sample-fn [:arglists 1 :types]))))))
