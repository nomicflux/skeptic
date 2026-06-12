(ns skeptic.worker.analysis-class-safety-test
  "Analyzing a source file must not (re)define any of its classes: ana.jvm's
   deftype* parse evals a method-less skeleton under the record's fixed name,
   and gen-interface (inside every defprotocol expansion) loads the interface
   class at macroexpansion time. Wrapper-macro records and plain defprotocols
   reach those paths; class identity must survive analysis."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [skeptic.test-examples.wrapped-record :as wrapped]
            [skeptic.worker.analyzer-clj :as wac]))

(deftest analysis-does-not-redefine-classes
  (let [rec-before (class (wrapped/make-wrapped))
        proto-before (clojure.lang.RT/classForName
                      "skeptic.test_examples.wrapped_record.WrappedProto")]
    (wac/analyze-source-file 'skeptic.test-examples.wrapped-record
                             (io/file "test/skeptic/test_examples/wrapped_record.clj")
                             false)
    (is (identical? rec-before
                    (clojure.lang.RT/classForName
                     "skeptic.test_examples.wrapped_record.WrappedRec")))
    (is (identical? proto-before
                    (clojure.lang.RT/classForName
                     "skeptic.test_examples.wrapped_record.WrappedProto")))
    (is (= 1 (wrapped/wp-value (wrapped/make-wrapped)))
        "protocol dispatch must still work after the file is analyzed")))
