(ns skeptic.checking.pipeline.namespace-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.source :as source]
            [skeptic.typed-decls :as typed-decls]))

(deftest regex-return-declaration-and-checking
  (let [{:keys [entries errors]} (typed-decls/typed-ns-results {} 'skeptic.test-examples.basics)
        declaration-error (some #(when (= 'skeptic.test-examples.basics/regex-return-caller
                                          (:blame %))
                                   %)
                                errors)
        namespace-results (ps/check-fixture-namespace 'skeptic.test-examples.basics
                                                      {:remove-context true})
        namespace-declaration-error (some #(when (and (= :declaration (:phase %))
                                                      (= 'skeptic.test-examples.basics/regex-return-caller
                                                         (:blame %)))
                                             %)
                                          namespace-results)]
    (is (contains? entries 'skeptic.test-examples.basics/regex-return-caller))
    (is (nil? declaration-error)
        (str "expected regex-return-caller to admit cleanly; got " (pr-str declaration-error)))
    (is (nil? namespace-declaration-error)
        (str "expected no declaration exception for regex-return-caller; got "
             (pr-str namespace-declaration-error)))
    (is (= [] (ps/check-fixture 'skeptic.test-examples.basics/regex-return-caller)))))

(deftest check-ns-uses-raw-forms
  (let [results (ps/check-fixture-ns 'skeptic.test-examples.basics
                                     {:remove-context true})]
    (is (seq results))
    (is (some #(= '(int-add x "hi") (:blame %)) results))
    (is (not-any? #(and (seq? (:blame %))
                        (= "schema.core" (namespace (first (:blame %)))))
                  results))))

(deftest check-ns-allows-empty-namespaces
  (require 'skeptic.core-fns)
  (is (= []
         (vec (sut/check-ns (typed-decls/typed-ns-entries {} 'skeptic.core-fns)
                            'skeptic.core-fns
                            (java.io.File. "src/skeptic/core_fns.clj")
                            {})))))

(deftest check-ns-localizes-read-failures
  (let [temp-file (doto (java.io.File/createTempFile "skeptic-read-failure" ".clj")
                    (.deleteOnExit))
        _ (spit temp-file "(ns skeptic.test-examples.basics)\n(def ok 1)\n(def broken [)\n")
        results (vec (sut/check-ns ps/test-dict
                                   'skeptic.test-examples.basics
                                   temp-file
                                   {:remove-context true}))]
    (is (= 1 (count results)))
    (is (= :exception (:report-kind (first results))))
    (is (= :read (:phase (first results))))
    (is (= (.getPath temp-file) (get-in (first results) [:location :file])))))

(deftest check-ns-reads-auto-resolved-keywords-in-target-ns
  (require 'skeptic.test-examples.resolution)
  (let [results (ps/check-fixture-ns 'skeptic.test-examples.resolution
                                     {:keep-empty true
                                      :remove-context true})]
    (is (seq results))
    (is (some #(= "(int-add x (::s/key2 y))" (:source-expression %)) results))
    (is (= {:blame '(int-add x (:schema.core/key2 y))
            :source-expression "(int-add x (::s/key2 y))"
            :expanded-expression '(int-add x (:schema.core/key2 y))
            :enclosing-form 'skeptic.test-examples.resolution/sample-namespaced-keyword-fn
            :focuses []}
           (some #(when (= "(int-add x (::s/key2 y))" (:source-expression %))
                    (dissoc (select-keys % [:blame :source-expression :expanded-expression :location :enclosing-form :focuses])
                            :location))
                 results)))
    (is (= {:file (ps/fixture-path-for-ns 'skeptic.test-examples.resolution)
            :line 9
            :column 5}
           (some #(when (= "(int-add x (::s/key2 y))" (:source-expression %))
                    (select-keys (:location %) [:file :line :column]))
                 results)))))

(deftest symbol-output-annotation-regression
  (let [form (->> 'skeptic.schema.collect/fully-qualify-str
                  (source/get-fn-code {})
                  read-string)
        results (vec (sut/check-s-expr ps/schema-collect-dict
                                       form
                                       {:ns 'skeptic.schema.collect
                                        :source-file ps/schema-collect-file
                                        :remove-context true}))]
    (is (= [] results))))

(deftest collect-annotations-output-annotation-regression
  (let [form (->> 'skeptic.schema.collect/collect-schemas
                  (source/get-fn-code {})
                  read-string)
        results (vec (sut/check-s-expr ps/schema-collect-dict
                                       form
                                       {:ns 'skeptic.schema.collect
                                        :source-file ps/schema-collect-file
                                        :remove-context true}))]
    (is (= [] results))))

(deftest static-call-examples-check-ns
  (let [results (vec (sut/check-ns ps/static-call-examples-dict
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
    (is (= [(incm/mismatched-output-schema-msg {:expr 'bad-count-default
                                                :arg '(get counts :count "zero")}
                                               (ato/union-type [(ps/T s/Int)
                                                                (ps/T s/Str)])
                                               (ps/T s/Int))]
           (:errors count-result)))
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
  (let [results (vec (sut/check-ns ps/examples-dict
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

(deftest namespace-checking-keeps-going-after-declaration-errors
  (let [{:keys [entries errors]} (typed-decls/typed-ns-results {} 'skeptic.best-effort-examples)
        results (vec (concat errors
                             (sut/check-ns entries
                                           'skeptic.best-effort-examples
                                           ps/best-effort-file
                                           {:remove-context true})))
        declaration-error (some #(when (= :declaration (:phase %)) %) results)
        stray-form-result (some #(when (= 'skeptic.best-effort-examples/good-call
                                        (:enclosing-form %))
                                   %)
                                results)]
    (is (some? declaration-error))
    (is (nil? stray-form-result))
    (is (= 1 (count results)))))

(deftest check-namespace-full-flow-localizes-declaration-errors
  (let [results (sut/check-namespace {:remove-context true}
                                     'skeptic.best-effort-examples
                                     ps/best-effort-file)
        declaration-errors (filterv #(= :declaration (:phase %)) results)
        expression-results (filterv #(not= :declaration (:phase %)) results)]
    (is (= 1 (count declaration-errors)))
    (is (= :exception (:report-kind (first declaration-errors))))
    (is (= 'skeptic.best-effort-examples/invalid-schema-decl
           (:blame (first declaration-errors))))
    (is (zero? (count expression-results)))))

(deftest check-namespace-localizes-load-failure
  (let [results (sut/check-namespace {}
                                     'skeptic.nonexistent.namespace.that.does.not.exist
                                     (java.io.File. "nonexistent.clj"))]
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
    (with-redefs [sut/analyze-source-exprs (fn [dict ns-sym source-file exprs]
                                             (if (= exploding-form (first exprs))
                                               (throw (ex-info "boom during analysis" {}))
                                               (real-analyze dict ns-sym source-file exprs)))]
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
    (with-redefs [sut/check-resolved-form (fn [dict ns-sym source-file source-form analyzed opts]
                                            (if (= exploding-form source-form)
                                              (map (fn [_]
                                                     (throw (ex-info "boom during realization" {})))
                                                   [::explode])
                                              (real-check-resolved-form dict ns-sym source-file source-form analyzed opts)))]
      (let [form-results (sut/check-ns-form ps/test-dict
                                            'skeptic.test-examples.basics
                                            file
                                            exploding-form
                                            {:remove-context true})
            exception-result (first form-results)
            results (ps/check-fixture-ns 'skeptic.test-examples.basics
                                         {:remove-context true})
            later-mismatch (some #(when (= 'skeptic.test-examples.basics/sample-mismatched-types
                                          (:enclosing-form %))
                                   %)
                                results)]
        (is (= 1 (count form-results)))
        (is (= :expression (:phase exception-result)))
        (is (= 'skeptic.test-examples.basics/sample-direct-nil-arg-fn
               (:enclosing-form exception-result)))
        (is (= "boom during realization" (:exception-message exception-result)))
        (is (some? later-mismatch))))))
