(ns skeptic.checking.pipeline.named-fold-regression-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.checking.pipeline :as checking]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.report :as inrep]))

(deftest named-fold-output-summary-regression
  (let [sym 'skeptic.test-examples.named-fold/named-fold-output-failure
        ns-sym (ps/fixture-ns sym)
        results (vec (ps/check-fixture sym {:remove-context true}))
        namespace-dict (checking/namespace-dict {} ns-sym (ps/fixture-file-for-ns ns-sym))
        fold-index (abr/build-fold-index (:dict namespace-dict)
                                         (:provenance namespace-dict))
        result (first results)
        folded-summary (inrep/report-summary result {:fold-index fold-index})
        error-text (ps/strip-ansi (first (:errors folded-summary)))]
    (is (seq results))
    (is (some? result))
    (is (seq (:errors result)))
    (is (map? fold-index))
    (is (seq fold-index))
    (is (some #(= 'skeptic.test-examples.named-fold/named-fold-output-failure
                  (:qualified-sym %))
              (vals fold-index)))
    (is (str/includes? error-text "output mismatch against the declared return type"))))
