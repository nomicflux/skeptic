(ns skeptic.checking.pipeline.canary-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.test-examples.catalog :as catalog]))

(deftest documented-fixture-canary
  (doseq [sym catalog/documented-canary-symbols]
    (try
      (let [results (ps/check-fixture sym)]
        (is (empty? results)
            (str "documented fixture failed for " sym ": " (pr-str results))))
      (catch Exception e
        (throw (ex-info "Exception checking documented fixture"
                        {:function sym
                         :error e}))))))
