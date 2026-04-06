 (ns skeptic.analysis.schema.valued-test
   (:require [clojure.test :refer [deftest is]]
             [schema.core :as s]
             [skeptic.analysis.schema.valued :as sut]
             [skeptic.analysis.schema-base :as sb]))

 (defn schema-or-value
   [schema value]
   (sb/valued-schema schema value))

 (deftest valued-helper-public-api-test
   (is (= 1 (sut/get-by-matching-schema {s/Symbol 1} s/Symbol)))
   (is (= 2 (sut/get-by-matching-schema {s/Int 2} s/Int)))
   (is (= 1 (sut/valued-get {:a 1 :b 2} :a)))
   (is (= 1 (sut/valued-get {:a 1 :b 2} (schema-or-value s/Keyword :a))))
   (is (= 2 (sut/valued-get {:a 1 s/Keyword 2} (schema-or-value s/Keyword :b))))
   (is (sut/valued-compatible? {s/Keyword s/Int :b s/Str} {:a 1 :b "x"}))
   (is (not (sut/valued-compatible? {s/Keyword s/Int :b s/Str} {:b 1 :a "x"})))
   (is (sut/valued-compatible? {:name s/Str
                                :schema (s/maybe s/Any)}
                               {(schema-or-value s/Keyword :name) (schema-or-value s/Str "x")
                                (schema-or-value s/Keyword :schema) (schema-or-value s/Int 1)}))
   (is (sut/matches-map {s/Keyword s/Str :b 2} :a "x"))
   (is (not (sut/matches-map {s/Keyword s/Str :b 2} :a 1))))

 (deftest schema-values-expands-valued-keys-and-values-test
   (let [schema {(schema-or-value s/Keyword :name) (schema-or-value s/Str "x")
                 :age s/Int}
         expanded (vec (sut/schema-values schema))]
     (is (= (set expanded) (set (sut/schema-values schema))))
     (is (= 2 (count expanded)))
     (is (every? #(= s/Int (:age %)) expanded))
     (is (some #(contains? % :name) expanded))
     (is (some #(contains? % s/Keyword) expanded))))
