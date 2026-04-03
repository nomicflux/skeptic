 (ns skeptic.analysis.schema.map-cast-test
   (:require [clojure.test :refer [deftest is]]
             [schema.core :as s]
             [skeptic.analysis.schema :as as]
             [skeptic.analysis.schema.cast-support :as ascs]
             [skeptic.analysis.schema.map-ops :as asm]
             [skeptic.analysis.schema.value-check :as asv]
             [skeptic.analysis.schema-base :as sb]))

 (deftest non-literal-get-uses-key-domain-regression-test
   (let [schema {:a s/Int
                 s/Keyword s/Str}
         literal-result (asm/map-get-schema schema
                                            (asm/exact-key-query s/Keyword :a :a))
         domain-result (asm/map-get-schema schema
                                           (asm/domain-key-query s/Keyword 'k))]
     (is (= s/Int literal-result))
     (is (ascs/schema-equivalent? (sb/join s/Int s/Str)
                                  domain-result))))

 (deftest mixed-map-schema-requiredness-regression-test
   (let [schema {:a s/Int :b s/Str s/Keyword s/Any}
         valid {:a 1 :b "hello"}
         valid-with-extra {:a 1 :b "hello" :c 5}
         missing-required {:a 1}
         cast-result (as/check-cast {:a s/Int :b s/Str} schema)]
     (is (nil? (s/check schema valid)))
     (is (nil? (s/check schema valid-with-extra)))
     (is (some? (s/check schema missing-required)))
     (is (asv/value-satisfies-type? valid schema))
     (is (asv/value-satisfies-type? valid-with-extra schema))
     (is (not (asv/value-satisfies-type? missing-required schema)))
     (is (:ok? cast-result))
     (is (= :map (:rule cast-result)))))

 (deftest extra-schema-row-does-not-imply-required-presence-regression-test
   (let [schema {s/Keyword s/Int}
         empty-value {}
         cast-result (as/check-cast empty-value schema)]
     (is (nil? (s/check schema empty-value)))
     (is (asv/value-satisfies-type? empty-value schema))
     (is (:ok? cast-result))
     (is (= :map (:rule cast-result)))))

 (deftest pattern-map-key-presence-classification-regression-test
   (is (= :unknown
          (asv/contains-key-classification {s/Keyword s/Any} :a)))
   (is (= :always
          (asv/contains-key-classification {:a s/Int s/Keyword s/Any} :a)))
   (is (= :unknown
          (asv/contains-key-classification {:a s/Int s/Keyword s/Any} :b))))
