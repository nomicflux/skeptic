(ns skeptic.analysis.cast.map-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.cast :as sut]
            [skeptic.analysis.cast.result :as cast-result]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest structural-map-cast-regressions-test
  (let [ok (sut/check-cast (T {:a s/Int :b s/Str})
                           (T {:a s/Int :b s/Str s/Keyword s/Any}))
        missing (sut/check-cast (T {:a s/Int})
                                (T {:a s/Int :b s/Str}))
        unexpected (sut/check-cast (T {:a s/Int :c s/Int})
                                   (T {:a s/Int :b s/Str}))
        domain-failure (sut/check-cast (T {s/Keyword s/Int})
                                       (T {:a s/Int :b s/Int}))
        nullable-key (sut/check-cast (T {(s/optional-key :a) s/Int})
                                     (T {:a s/Int}))]
    (is (:ok? ok))
    (is (= :map (:rule ok)))
    (is (some #(= :missing-key (:reason %)) (cast-result/leaf-diagnostics missing)))
    (is (some #(= :unexpected-key (:reason %)) (cast-result/leaf-diagnostics unexpected)))
    (is (some #(= :map-key-domain-not-covered (:reason %))
              (cast-result/leaf-diagnostics domain-failure)))
    (is (some #(= :nullable-key (:reason %)) (cast-result/leaf-diagnostics nullable-key)))))

(deftest map-leaf-paths-stay-visible-test
  (let [result (sut/check-cast (T {:user {:name s/Keyword}})
                               (T {:user {:name s/Str}}))
        [leaf] (cast-result/leaf-diagnostics result)]
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (:path leaf)))))
