(ns skeptic.analysis.cast.support-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as cast]
            [skeptic.analysis.cast.result :as cast-result]
            [skeptic.analysis.cast.support :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(def tp (prov/make-provenance :inferred 'test-sym 'skeptic.test nil))

(defn T
  [schema]
  (ab/schema->type tp schema))

(deftest live-support-api-test
  (let [wrapped (at/->OptionalKeyT tp (T s/Int))
        plain (T s/Str)
        result (sut/with-cast-path (sut/cast-fail (T s/Keyword)
                                                  (T s/Str)
                                                  :leaf-overlap
                                                  :positive
                                                  :leaf-mismatch)
                 {:kind :map-key
                  :key :name})]
    (is (= (T s/Int) (sut/optional-key-inner wrapped)))
    (is (= plain (sut/optional-key-inner plain)))
    (is (= [{:kind :map-key :key :name}] (:path result)))))

(deftest cast-results-reject-missing-types-test
  (let [capture (fn [f]
                  (try
                    (f)
                    (catch clojure.lang.ExceptionInfo e
                      e)))
        source-ex (capture #(sut/cast-fail nil
                                           (T s/Str)
                                           :leaf-overlap
                                           :positive
                                           :leaf-mismatch))
        target-ex (capture #(sut/cast-fail (T s/Keyword)
                                           nil
                                           :leaf-overlap
                                           :positive
                                           :leaf-mismatch))]
    (is (instance? clojure.lang.ExceptionInfo source-ex))
    (is (= {:rule :leaf-overlap
            :reason :leaf-mismatch
            :missing-field :source-type}
           (select-keys (ex-data source-ex) [:rule :reason :missing-field])))
    (is (contains? (ex-data source-ex) :cast-result-inputs))
    (is (instance? clojure.lang.ExceptionInfo target-ex))
    (is (= {:rule :leaf-overlap
            :reason :leaf-mismatch
            :missing-field :target-type}
           (select-keys (ex-data target-ex) [:rule :reason :missing-field])))
    (is (contains? (ex-data target-ex) :cast-result-inputs))))

(deftest leaf-diagnostics-test
  (let [result (cast/check-cast (T {:user {:name s/Keyword}})
                                (T {:user {:name s/Str}}))
        leaves (cast-result/leaf-diagnostics result)]
    (is (= 1 (count leaves)))
    (is (= :leaf-mismatch (:reason (first leaves))))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (:path (first leaves))))))
