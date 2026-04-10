(ns skeptic.analysis.cast.support-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as cast]
            [skeptic.analysis.cast.support :as sut]
            [skeptic.inconsistence.path :as path]
            [skeptic.analysis.types :as at]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest live-support-api-test
  (let [wrapped (at/->OptionalKeyT (T s/Int))
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

(deftest cast-leaf-results-compatibility-test
  (let [result (cast/check-cast (T {:user {:name s/Keyword}})
                                (T {:user {:name s/Str}}))
        leaves (path/cast-leaf-results result)]
    (is (= 1 (count leaves)))
    (is (= :leaf-mismatch (:reason (first leaves))))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (:path (first leaves))))))
