(ns skeptic.checking.pipeline.reporting-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.output.text :as output-text]))

(deftest call-mismatch-reports-affected-input-and-location
  (let [results (ps/check-fixture-ns 'skeptic.test-examples.control-flow
                                     {:remove-context true})
        result (some #(when (= '(int-add x y) (:blame %))
                        %)
                     results)]
    (is (= ['y] (:focuses result)))
    (is (= ["y"] (:focus-sources result)))
    (is (= "(int-add x y)" (:source-expression result)))
    (is (= {:file (ps/fixture-path-for-ns 'skeptic.test-examples.control-flow)
            :line 13
            :column 5}
           (select-keys (:location result) [:file :line :column])))
    (is (= 'skeptic.test-examples.control-flow/sample-nil-local-arg-fn
           (:enclosing-form result)))))

(deftest call-mismatch-summary-uses-single-focused-input
  (let [result (first (ps/check-fixture 'skeptic.test-examples.resolution/sample-let-fn-bad1-fn))
        summary (inrep/report-summary result)
        [error] (:errors summary)]
    (is (= '(int-add y nil) (:blame result)))
    (is (re-find #"(?s)^nil\s+\tin\s+\(int-add y nil\)\s+" (ps/strip-ansi error)))
    (is (not (re-find #"(?s)^\(int-add y nil\)\s+\tin\s+\(int-add y nil\)\s+" (ps/strip-ansi error))))
    (is (or (str/includes? (ps/strip-ansi error) "expected type")
            (str/includes? (ps/strip-ansi error) "is nullable, but expected is not")))))

(deftest output-mismatch-renders-canonical-map-types
  (let [results (vec (sut/check-ns ps/static-call-examples-dict
                                   'skeptic.static-call-examples
                                   ps/static-call-examples-file
                                   {:remove-context true}))
        result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                              (:enclosing-form %))
                        %)
                     results)
        error (first (:errors result))]
    (is (some? result))
    (is (.contains error "{:name Keyword, :nickname (maybe Str)}"))
    (is (.contains error "{:name Str, :nickname (maybe Str)}"))
    (is (not (.contains error "\":name : Keyword\"")))))

(deftest output-summary-highlights-path-or-drops-redundant-self-context
  (let [results (vec (sut/check-ns ps/static-call-examples-dict
                                   'skeptic.static-call-examples
                                   ps/static-call-examples-file
                                   {:remove-context true}))
        count-result (some #(when (= 'skeptic.static-call-examples/bad-count-default
                                      (:enclosing-form %))
                              %)
                           results)
        rebuilt-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                             (:enclosing-form %))
                                     %)
                                  results)
        count-error (-> count-result inrep/report-summary :errors first ps/strip-ansi)
        rebuilt-error (-> rebuilt-user-result inrep/report-summary :errors first ps/strip-ansi)]
    (is (re-find #"(?s)^\(get counts :count \"zero\"\)\s+has an output mismatch against the declared return type\." count-error))
    (is (not (re-find #"(?s)^\(get counts :count \"zero\"\)\s+\tin\s+\(get counts :count \"zero\"\)" count-error)))
    (is (str/includes? count-error "(union Int Str) but expected Int"))
    (is (re-find #"(?s)^\[:name\]\s+\tin\s+\{:name :bad, :nickname \(get user :nickname\)\}" rebuilt-error))
    (is (str/includes? rebuilt-error "[:name] has Keyword but expected Str"))))

(deftest nested-output-mismatch-renders-field-paths
  (let [results (vec (sut/check-ns ps/static-call-examples-dict
                                   'skeptic.static-call-examples
                                   ps/static-call-examples-file
                                   {:remove-context true}))
        result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-nested-user
                              (:enclosing-form %))
                        %)
                     results)]
    (is (some? result))
    (is (some #(str/includes? % "declared return type") (:errors result)))
    (is (= 1 (count (:errors result))))
    (is (not-any? #(str/includes? % "[:user :name]") (:errors result)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> result :cast-diagnostics first :path)))))

(deftest check-results-carry-cast-metadata
  (let [results (vec (sut/check-ns ps/static-call-examples-dict
                                   'skeptic.static-call-examples
                                   ps/static-call-examples-file
                                   {:remove-context true}))
        nested-result (some #(when (= 'skeptic.static-call-examples/nested-multi-step-failure
                                      (:enclosing-form %))
                               %)
                            results)
        output-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                      (:enclosing-form %))
                               %)
                            results)]
    (doseq [result [nested-result output-result]]
      (is (some? result))
      (is (= :term (:blame-side result)))
      (is (= :positive (:blame-polarity result)))
      (is (keyword? (:rule result)))
      (is (some? (:expected-type result)))
      (is (some? (:actual-type result)))
      (is (map? (:cast-summary result)))
      (is (seq (:cast-diagnostics result))))
    (is (= "(nested-multi-step-takes-str (get (nested-multi-step-g) :value))"
           (:source-expression nested-result)))
    (is (= {:file "src/skeptic/static_call_examples.clj"
            :line 88
            :column 3}
           (select-keys (:location nested-result) [:file :line :column])))
    (is (= ["(get (nested-multi-step-g) :value)"]
           (:focus-sources nested-result)))))

(deftest printer-path-renders-only-user-facing-data
  (let [result (first (ps/check-fixture 'skeptic.test-examples.collections/nested-map-input-failure
                                        {:remove-context true}))
        summary (inrep/report-summary result)
        printed (str/join "\n"
                          (concat (map (fn [[label value]]
                                         (str label value))
                                       (output-text/report-fields summary))
                                  (:errors summary)))]
    (is (some? result))
    (is (str/includes? printed "[:user :name]"))
    (ps/assert-no-ui-internals printed)))
