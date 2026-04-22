(ns skeptic.output.porcelain-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.types :as at]
            [skeptic.output.porcelain :as sut]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil))

(defn- capture-lines
  [f]
  (-> (with-out-str (f))
      (str/split-lines)
      vec))

(defn- parse-line [s] (json/read-str s :key-fn keyword))

(def ^:private example-input-summary
  {:report-kind :input
   :location {:file "src/foo.clj" :line 42 :column 3 :source :malli-spec}
   :blame-side :term
   :blame-polarity :positive
   :source-expression "(+ 1 :x)"
   :blame '(+ 1 :x)
   :focuses []
   :focus-sources ["x"]
   :enclosing-form 'foo.bar/f
   :expanded-expression '(clojure.core/+ 1 x)
   :rule :ground-mismatch
   :actual-type (at/->GroundT tp :keyword 'Keyword)
   :expected-type (at/->GroundT tp :int 'Int)
   :errors ["\u001b[33mKeyword is not compatible with Int\u001b[0m"]})

(def ^:private example-exception-summary
  {:report-kind :exception
   :phase :declaration
   :location {:file "src/foo.clj" :line 99 :column 1 :source :schema}
   :blame 'my-fn
   :errors ["Skeptic hit an exception while checking declared schema for my-fn ..."]})

(def ^:private example-exception-result
  {:exception-class 'java.lang.RuntimeException
   :exception-message "could not resolve schema"})

(deftest finding-record-shape
  (let [[line & more] (capture-lines
                       #((:finding sut/printer) 'foo.bar {} example-input-summary {}))
        parsed (parse-line line)]
    (is (empty? more))
    (is (= "finding" (:kind parsed)))
    (is (= "foo.bar" (:ns parsed)))
    (is (= "input" (:report_kind parsed)))
    (is (= "src/foo.clj" (get-in parsed [:location :file])))
    (is (= 42 (get-in parsed [:location :line])))
    (is (= 3 (get-in parsed [:location :column])))
    (is (= "malli-spec" (get-in parsed [:location :source])))
    (is (= "term" (:blame_side parsed)))
    (is (= "positive" (:blame_polarity parsed)))
    (is (= "ground-mismatch" (:rule parsed)))
    (is (= "Int" (get-in parsed [:expected_type :name])))
    (is (= "Keyword" (get-in parsed [:actual_type :name])))
    (is (= "Int" (:expected_type_str parsed)))
    (is (= "Keyword" (:actual_type_str parsed)))
    (is (= ["x"] (:focuses parsed)))
    (is (= ["Keyword is not compatible with Int"] (:messages parsed)))
    (testing "ANSI stripped"
      (is (not (re-find #"\u001b" line))))))

(deftest finding-record-carries-source-for-every-source-kind
  (doseq [src [:inferred :schema :malli-spec :native :type-override]]
    (let [summary (assoc example-input-summary
                         :location {:file "f.clj" :line 1 :column 2 :source src})
          [line] (capture-lines
                  #((:finding sut/printer) 'foo.bar {} summary {}))
          parsed (parse-line line)]
      (is (= (name src) (get-in parsed [:location :source]))
          (str "source kind " src " must be serialized")))))

(deftest exception-record-shape
  (let [[line] (capture-lines
                #((:finding sut/printer) 'foo.bar example-exception-result
                                         example-exception-summary {}))
        parsed (parse-line line)]
    (is (= "exception" (:kind parsed)))
    (is (= "declaration" (:phase parsed)))
    (is (= "java.lang.RuntimeException" (:exception_class parsed)))
    (is (= "could not resolve schema" (:exception_message parsed)))
    (is (= 99 (get-in parsed [:location :line])))
    (is (= "schema" (get-in parsed [:location :source])))
    (is (= "my-fn" (:blame parsed)))))

(deftest discovery-warning-shape
  (let [[line] (capture-lines
                #((:discovery-warn sut/printer) {:path "src/broken.clj"
                                                 :message "parse error"}))
        parsed (parse-line line)]
    (is (= "ns-discovery-warning" (:kind parsed)))
    (is (= "src/broken.clj" (:path parsed)))
    (is (= "parse error" (:message parsed)))))

(deftest run-summary-always-emitted
  (testing "clean run still emits one record"
    (let [[line & more] (capture-lines
                         #((:run-end sut/printer)
                           false
                           {:finding-count 0 :exception-count 0
                            :namespace-count 3 :namespaces-with-findings 0}))
          parsed (parse-line line)]
      (is (empty? more))
      (is (= "run-summary" (:kind parsed)))
      (is (false? (:errored parsed)))
      (is (= 3 (:namespace_count parsed)))))
  (testing "errored run"
    (let [[line] (capture-lines
                  #((:run-end sut/printer)
                    true
                    {:finding-count 7 :exception-count 1
                     :namespace-count 3 :namespaces-with-findings 2}))
          parsed (parse-line line)]
      (is (true? (:errored parsed)))
      (is (= 7 (:finding_count parsed)))
      (is (= 1 (:exception_count parsed)))
      (is (= 2 (:namespaces_with_findings parsed))))))

(deftest lifecycle-noops-produce-no-output
  (is (empty? (str/trim (with-out-str ((:run-start sut/printer) {} {})))))
  (is (empty? (str/trim (with-out-str ((:ns-start sut/printer) 'foo nil {})))))
  (is (empty? (str/trim (with-out-str ((:ns-end sut/printer) 'foo 0 {}))))))

(deftest debug-form-record-is-verbatim-edn-dump
  (testing "one line, containing the EDN of the record as emitted at the wire-tap"
    (let [record {:report-kind :debug-form
                  :ns 'foo.bar
                  :source-form '(defn f [x] x)}
          [line & more] (capture-lines
                         #((:form-debug sut/printer) 'foo.bar record {}))
          parsed (parse-line line)]
      (is (empty? more))
      (is (string? parsed))
      (is (= record (read-string parsed))))))

(deftest finding-with-debug-opt-carries-raw-result
  (let [result {:report-kind :input
                :cast-summary {:foo 'bar}
                :context {:local-vars {}}}
        [line] (capture-lines
                #((:finding sut/printer) 'foo.bar result example-input-summary
                                         {:debug true}))
        parsed (parse-line line)]
    (is (= "finding" (:kind parsed)))
    (is (string? (get-in parsed [:debug :raw-result])))
    (is (= :input (:report-kind (read-string (get-in parsed [:debug :raw-result])))))))

(deftest finding-without-debug-opt-omits-debug-key
  (let [[line] (capture-lines
                #((:finding sut/printer) 'foo.bar {} example-input-summary {}))
        parsed (parse-line line)]
    (is (nil? (:debug parsed)))))

(deftest explain-full-controls-named-folding-in-porcelain
  (let [schema-prov (prov/make-provenance :schema 'foo/NamedVec 'foo.ns nil)
        inferred-prov (prov/make-provenance :inferred 'foo.caller/f 'foo.caller nil)
        named-type (at/->VectorT schema-prov [(at/->GroundT schema-prov :int 'Int)] false)
        actual-type (at/->VectorT inferred-prov [(at/->GroundT inferred-prov :int 'Int)] false)
        fold-index (abr/build-fold-index {'foo/NamedVec named-type}
                                         {'foo/NamedVec schema-prov})
        summary (assoc example-input-summary
                       :actual-type actual-type
                       :expected-type actual-type)
        [folded-line] (capture-lines
                       #((:finding sut/printer) 'foo.bar {} summary {:fold-index fold-index}))
        [full-line] (capture-lines
                     #((:finding sut/printer) 'foo.bar {} summary {:fold-index fold-index
                                                                    :explain-full true}))
        folded (parse-line folded-line)
        full (parse-line full-line)]
    (is (= "named" (get-in folded [:actual_type :t])))
    (is (= "foo/NamedVec" (get-in folded [:actual_type :name])))
    (is (= "schema" (get-in folded [:actual_type :source])))
    (is (= "foo/NamedVec" (:actual_type_str folded)))
    (is (= "vector" (get-in full [:actual_type :t])))
    (is (= "[Int]" (:actual_type_str full)))))
