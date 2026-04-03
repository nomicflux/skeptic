(ns skeptic.schematize-test
  (:require [skeptic.schematize :as sut]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]))

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
