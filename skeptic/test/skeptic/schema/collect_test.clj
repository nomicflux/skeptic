(ns skeptic.schema.collect-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.schema.collect :as sut])
  (:import [java.io File]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(deftest arg-list-only-varargs
  (is (= {:count 2, :args '[x y], :with-varargs false, :varargs []}
         (sut/arg-list '[x y])))
  (is (= {:count 1, :args [], :with-varargs true, :varargs '[rest]}
         (sut/arg-list '[& rest])))
  (is (= {:count 2, :args '[x], :with-varargs true, :varargs '[rest]}
         (sut/arg-list '[x & rest]))))

(deftest ns-schemas-only-contains-annotated-vars
  (require 'skeptic.test-examples.resolution)
  (let [schemas (sut/ns-schemas {} 'skeptic.test-examples.resolution)]
    (is (contains? schemas 'skeptic.test-examples.resolution/flat-multi-step-f))
    (is (not (contains? schemas 'skeptic.test-examples.resolution/sample-namespaced-keyword-fn)))))

(deftest ns-schemas-reads-auto-resolved-keywords-in-source-namespaces
  (require 'skeptic.inconsistence.report)
  (is (map? (sut/ns-schemas {} 'skeptic.inconsistence.report))))

(deftest collect-schemas-canonicalizes-schema-representations
  (let [symbol-desc (sut/collect-schemas {:schema (s/make-fn-schema clojure.lang.Symbol
                                                                   [[(s/one java.lang.String 'f)]])
                                          :ns 'skeptic.schema.collect
                                          :name 'raw-symbol-fn
                                          :arglists '([f])})
        keyword-desc (sut/collect-schemas {:schema (s/make-fn-schema clojure.lang.Keyword
                                                                    [[(s/one java.lang.Integer 'x)]])
                                           :ns 'skeptic.schema.collect
                                           :name 'raw-keyword-fn
                                           :arglists '([x])})]
    (is (= s/Symbol (:output symbol-desc)))
    (is (= s/Str (get-in symbol-desc [:arglists 1 :schema 0 :schema])))
    (is (= s/Keyword (:output keyword-desc)))
    (is (= s/Int (get-in keyword-desc [:arglists 1 :schema 0 :schema])))))

(deftest ns-schemas-canonicalizes-known-public-schemas
  (require 'skeptic.schema.collect)
  (let [schemas (sut/ns-schemas {} 'skeptic.schema.collect)]
    (is (= s/Symbol
           (get-in schemas ['skeptic.schema.collect/fully-qualify-str :output])))
    (is (= s/Str
           (get-in schemas ['skeptic.schema.collect/fully-qualify-str :arglists 1 :schema 0 :schema])))))

(deftest collect-schemas-rejects-invalid-schema-annotations-early
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Invalid schema annotation"
                        (sut/collect-schemas {:schema [(at/->GroundT tp :int 'Int)]
                                              :ns 'skeptic.schema.collect
                                              :name 'invalid
                                              :arglists '([])}))))

(deftest collect-schemas-admits-regex-and-rejects-semantic-type-nested-args
  (let [regex-schema (s/make-fn-schema s/Int
                                       [[(s/one #"^[\u0020-\u007e]*$" 'x)]])
        invalid-semantic-type-schema (s/make-fn-schema s/Int
                                                       [[(s/one (at/->GroundT tp :int 'Int)
                                                                'x)]])]
    (let [regex (get-in (sut/collect-schemas {:schema regex-schema
                                              :ns 'skeptic.schema.collect
                                              :name 'regex-ok
                                              :arglists '([x])})
                        [:arglists 1 :schema 0 :schema])]
      (is (instance? java.util.regex.Pattern regex))
      (is (= "^[\\u0020-\\u007e]*$" (.pattern regex))))
    (is (thrown-with-msg? IllegalArgumentException
                          #"Expected schema value"
                          (sut/collect-schemas {:schema invalid-semantic-type-schema
                                                :ns 'skeptic.schema.collect
                                                :name 'invalid-semantic-type
                                                :arglists '([x])})))))

(deftest ns-schema-results-localizes-invalid-declarations
  (require 'skeptic.best-effort-examples)
  (let [{:keys [entries errors]} (sut/ns-schema-results {} 'skeptic.best-effort-examples)]
    (is (contains? entries 'skeptic.best-effort-examples/ok-plus))
    (is (contains? entries 'skeptic.best-effort-examples/good-call))
    (is (= 1 (count errors)))
    (is (= :exception (:report-kind (first errors))))
    (is (= :declaration (:phase (first errors))))
    (is (= 'skeptic.best-effort-examples/invalid-schema-decl
           (:blame (first errors))))))

(deftest build-annotation-refs-populates-defn-and-def-entries
  (require 'skeptic.test-examples.annotation-refs)
  (let [ns-sym 'skeptic.test-examples.annotation-refs
        file (File. "test/skeptic/test_examples/annotation_refs.clj")
        refs (sut/build-annotation-refs! (java.util.IdentityHashMap.) ns-sym file)
        ref-schema-var (resolve 'skeptic.test-examples.annotation-refs/RefSchema)
        annotated-fn-var (resolve 'skeptic.test-examples.annotation-refs/annotated-fn)
        annotated-val-var (resolve 'skeptic.test-examples.annotation-refs/annotated-val)]
    (is (= ref-schema-var (.get refs annotated-fn-var)))
    (is (= ref-schema-var (.get refs annotated-val-var)))
    (is (= 2 (.size refs)))))

(deftest build-annotation-refs-skips-unannotated-forms
  (require 'skeptic.test-examples.annotation-refs)
  (let [ns-sym 'skeptic.test-examples.annotation-refs
        file (File. "test/skeptic/test_examples/annotation_refs.clj")
        refs (sut/build-annotation-refs! (java.util.IdentityHashMap.) ns-sym file)
        ref-schema-var (resolve 'skeptic.test-examples.annotation-refs/RefSchema)]
    (is (nil? (.get refs ref-schema-var)))))

(deftest build-annotation-refs-skips-unresolvable-annotation
  (let [ns-sym 'skeptic.test-examples.annotation-refs
        tmp (java.io.File/createTempFile "annotation-refs-test" ".clj")]
    (spit tmp "(ns skeptic.test-examples.annotation-refs (:require [schema.core :as s])) (s/def orphan :- NoSuchVar 99)")
    (let [refs (sut/build-annotation-refs! (java.util.IdentityHashMap.) ns-sym tmp)]
      (is (zero? (.size refs))))
    (.delete tmp)))
