(ns skeptic.checking.pipeline.reporting-phase-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.provenance :as prov]))

(deftest output-mismatch-renders-canonical-map-types
  (let [results (:results (sut/check-ns 'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true
                                         :accessor-summaries (ps/summaries-for 'skeptic.static-call-examples ps/static-call-examples-file)}))
        result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                              (:enclosing-form %))
                        %)
                     results)
        error (first (:errors result))]
    (is (some? result))
    (is (.contains error "{:name Keyword, :nickname (maybe Str)}"))
    (is (.contains error "skeptic.static-call-examples/UserDesc"))
    (is (not (.contains error "\":name : Keyword\"")))))

(deftest output-summary-highlights-path-or-drops-redundant-self-context
  (let [results (:results (sut/check-ns 'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true
                                         :accessor-summaries (ps/summaries-for 'skeptic.static-call-examples ps/static-call-examples-file)}))
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
    (is (re-find #"\(union (Int Str|Str Int)\) but expected Int" count-error))
    (is (re-find #"(?s)^\[:name\]\s+\tin\s+\{:name :bad, :nickname \(get user :nickname\)\}" rebuilt-error))
    (is (str/includes? rebuilt-error "[:name] has Keyword but expected Str"))))

(deftest nested-output-mismatch-renders-field-paths
  (let [results (:results (sut/check-ns 'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true
                                         :accessor-summaries (ps/summaries-for 'skeptic.static-call-examples ps/static-call-examples-file)}))
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

(deftest namespace-dict-surfaces-schema-source-for-schema-declared-syms
  (let [{:keys [provenance]} (sut/check-namespace {:remove-context true
                                                   :accessor-summaries (ps/summaries-for 'skeptic.static-call-examples ps/static-call-examples-file)}
                                                  'skeptic.static-call-examples
                                                  ps/static-call-examples-file)]
    (is (= :schema (prov/source (get provenance 'skeptic.static-call-examples/bad-rebuilt-user))))
    (is (= :inferred (prov/source (get provenance 'skeptic.static-call-examples/nested-multi-step-failure))))))

(deftest check-results-carry-cast-metadata
  (let [results (:results (sut/check-ns 'skeptic.static-call-examples
                                        ps/static-call-examples-file
                                        {:remove-context true
                                         :accessor-summaries (ps/summaries-for 'skeptic.static-call-examples ps/static-call-examples-file)}))
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
