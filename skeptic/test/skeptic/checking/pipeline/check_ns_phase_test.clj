(ns skeptic.checking.pipeline.check-ns-phase-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.provenance :as prov]
            [skeptic.test-support.project-state :as test-state]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(deftest check-ns-allows-empty-namespaces
  (require 'skeptic.core-fns)
  (let [file (java.io.File. "src/skeptic/core_fns.clj")]
    (is (= []
           (:results (sut/check-ns (ps/project-state-for 'skeptic.core-fns file)
                                   'skeptic.core-fns
                                   file
                                   {}))))))

(deftest check-namespace-localizes-read-failures
  (let [temp-file (doto (java.io.File/createTempFile "skeptic-read-failure" ".clj")
                    (.deleteOnExit))
        _ (spit temp-file "(ns skeptic.test-examples.basics)\n(def ok 1)\n(def broken [)\n")
        {:keys [results]} (sut/check-namespace (ps/project-state-for 'skeptic.test-examples.basics temp-file)
                                               'skeptic.test-examples.basics
                                               temp-file
                                               {:remove-context true})]
    (is (= 1 (count results)))
    (is (= :exception (:report-kind (first results))))
    (is (= :read (:phase (first results))))))

(deftest symbol-output-annotation-regression
  (let [results (filterv #(= 'skeptic.schema.collect/fully-qualify-str
                             (:enclosing-form %))
                         (:results (sut/check-ns (ps/project-state-for 'skeptic.schema.collect ps/schema-collect-file)
                                                 'skeptic.schema.collect
                                                 ps/schema-collect-file
                                                 {:remove-context true})))]
    (is (= [] results))))

(deftest collect-annotations-output-annotation-regression
  (let [results (filterv #(= 'skeptic.schema.collect/collect-schemas
                             (:enclosing-form %))
                         (:results (sut/check-ns (ps/project-state-for 'skeptic.schema.collect ps/schema-collect-file)
                                                 'skeptic.schema.collect
                                                 ps/schema-collect-file
                                                 {:remove-context true})))]
    (is (= [] results))))

(deftest static-call-examples-check-ns
  (let [results (:results (sut/check-ns (ps/project-state-for 'skeptic.static-call-examples ps/static-call-examples-file)
                                        'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true}))
        count-result (some #(when (= 'skeptic.static-call-examples/bad-count-default
                                      (:enclosing-form %))
                              %)
                           results)
        nested-call-result (some #(when (= 'skeptic.static-call-examples/nested-multi-step-failure
                                            (:enclosing-form %))
                                    %)
                                 results)
        rebuilt-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                             (:enclosing-form %))
                                     %)
                                  results)
        rebuilt-nested-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-nested-user
                                                    (:enclosing-form %))
                                            %)
                                         results)]
    (is (= 1 (count (:errors count-result))))
    (is (re-find #"(?s)\(get counts :count \"zero\"\).*in.*bad-count-default.*has inferred output type:.*\(union (Int Str|Str Int)\).*but the declared return type expects:.*Int"
                 (first (:errors count-result))))
    (is (= [(incm/mismatched-ground-type-msg
             {:expr '(nested-multi-step-takes-str (get (nested-multi-step-g) :value))
              :arg '(. clojure.lang.RT (clojure.core/get (nested-multi-step-g) :value))}
             (ps/T s/Int)
             (ps/T s/Str))]
           (:errors nested-call-result)))
    (is (some #(str/includes? % "{:name Keyword, :nickname (maybe Str)}")
              (:errors rebuilt-user-result)))
    (is (= 1 (count (:errors rebuilt-user-result))))
    (is (not-any? #(str/includes? % "Problem fields:") (:errors rebuilt-user-result)))
    (is (not-any? #(str/includes? % "[:user :name]") (:errors rebuilt-nested-user-result)))
    (is (not-any? #(contains? #{'skeptic.static-call-examples/required-name
                                'skeptic.static-call-examples/optional-nickname
                                'skeptic.static-call-examples/nickname-with-default
                                'skeptic.static-call-examples/rebuilt-user
                                'skeptic.static-call-examples/rebuilt-nested-user
                                'skeptic.static-call-examples/merge-fields
                                'skeptic.static-call-examples/nested-multi-step-success}
                              (:enclosing-form %))
                   results))))

(deftest examples-maybe-multi-step-check-ns
  (let [results (:results (sut/check-ns (ps/project-state-for 'skeptic.examples ps/examples-file)
                                        'skeptic.examples
                                        ps/examples-file
                                        {:remove-context true}))]
    (is (some #(when (= 'skeptic.examples/flat-maybe-base-type-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/flat-maybe-nil-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/nested-maybe-base-type-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/nested-maybe-nil-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (nil? (some #(when (= 'skeptic.examples/flat-maybe-success
                             (:enclosing-form %))
                    %)
                  results)))
    (is (nil? (some #(when (= 'skeptic.examples/nested-maybe-success
                             (:enclosing-form %))
                    %)
                  results)))))

(deftest check-namespace-localizes-load-failure
  (let [valid-ns 'skeptic.test-examples.basics
        valid-file (ps/fixture-file-for-ns valid-ns)
        {:keys [results]} (sut/check-namespace (ps/project-state-for valid-ns valid-file)
                                               'skeptic.nonexistent.namespace.that.does.not.exist
                                               (java.io.File. "nonexistent.clj")
                                               {})]
    (is (= 1 (count results)))
    (is (= :exception (:report-kind (first results))))
    (is (= :load (:phase (first results))))
    (is (= 'skeptic.nonexistent.namespace.that.does.not.exist
           (:namespace (first results))))))

(deftest check-ns-localizes-expression-exceptions-and-continues
  (let [real-analyze sut/analyze-source-exprs
        file (ps/fixture-file-for-ns 'skeptic.test-examples.basics)
        exprs (vec (sut/ns-exprs file))
        exploding-form (some #(when (= 'sample-direct-nil-arg-fn (second %)) %) exprs)]
    (is (some? exploding-form))
    (with-redefs [sut/analyze-source-exprs
                  (fn [dict ns-sym source-file exprs accessor-summaries cljs-state lang]
                    (if (= exploding-form (first exprs))
                      (throw (ex-info "boom during analysis" {}))
                      (real-analyze dict ns-sym source-file exprs accessor-summaries cljs-state lang)))]
      (let [results (ps/check-fixture-ns 'skeptic.test-examples.basics
                                         {:remove-context true})
            exception-result (some #(when (= :expression (:phase %)) %) results)
            later-mismatch (some #(when (= 'skeptic.test-examples.basics/sample-mismatched-types
                                          (:enclosing-form %))
                                   %)
                                results)]
        (is (some? exception-result))
        (is (= 'skeptic.test-examples.basics/sample-direct-nil-arg-fn
               (:enclosing-form exception-result)))
        (is (= "boom during analysis" (:exception-message exception-result)))
        (is (some? later-mismatch))))))

(deftest check-ns-localizes-lazy-expression-exceptions-and-continues
  (let [real-check-resolved-form sut/check-resolved-form
        file (ps/fixture-file-for-ns 'skeptic.test-examples.basics)
        exprs (vec (sut/ns-exprs file))
        exploding-form (some #(when (= 'sample-direct-nil-arg-fn (second %)) %) exprs)]
    (is (some? exploding-form))
    (with-redefs [sut/check-resolved-form (fn [dict ignore-body ns-sym source-file source-form analyzed opts]
                                            (if (= exploding-form source-form)
                                              (map (fn [_]
                                                     (throw (ex-info "boom during realization" {})))
                                                   [::explode])
                                              (real-check-resolved-form dict ignore-body ns-sym source-file source-form analyzed opts)))]
      (let [{:keys [dict ignore-body]} (test-state/admit-ns 'skeptic.test-examples.basics file)
            form-results (sut/check-ns-form dict
                                            ignore-body
                                            'skeptic.test-examples.basics
                                            file
                                            exploding-form
                                            {}
                                            {}
                                            :clj
                                            {:remove-context true})
            exception-result (first (:results form-results))
            results (ps/check-fixture-ns 'skeptic.test-examples.basics
                                         {:remove-context true})
            later-mismatch (some #(when (= 'skeptic.test-examples.basics/sample-mismatched-types
                                          (:enclosing-form %))
                                   %)
                                results)]
        (is (= 1 (count (:results form-results))))
        (is (= :expression (:phase exception-result)))
        (is (= 'skeptic.test-examples.basics/sample-direct-nil-arg-fn
               (:enclosing-form exception-result)))
        (is (= "boom during realization" (:exception-message exception-result)))
        (is (some? later-mismatch))))))
