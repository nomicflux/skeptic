 (ns skeptic.analysis.schema.map-cast-test
   (:require [clojure.test :refer [deftest is]]
             [schema.core :as s]
             [skeptic.analysis.schema.map-ops :as asm]
             [skeptic.analysis.schema.value-check :as asv]
             [skeptic.analysis.schema-base :as sb]))

 (defn schema-or-value
   [schema value]
   (sb/valued-schema schema value))

 (deftest raw-map-lookup-regression-test
   (let [schema {:a s/Int
                 s/Keyword s/Str}
         optional-schema {(s/optional-key :a) s/Int}
         valued-key (schema-or-value s/Keyword :b)]
     (is (= s/Int (asm/map-get-schema schema :a)))
     (is (= s/Str (asm/map-get-schema schema :b)))
     (is (= s/Str (asm/map-get-schema schema valued-key)))
     (is (= (s/maybe s/Int) (asm/map-get-schema optional-schema :a)))))

 (deftest pattern-map-key-presence-classification-regression-test
   (is (= :unknown
          (asv/contains-key-classification {s/Keyword s/Any} :a)))
   (is (= :always
          (asv/contains-key-classification {:a s/Int s/Keyword s/Any} :a)))
   (is (= :unknown
          (asv/contains-key-classification {:a s/Int s/Keyword s/Any} :b))))

 (deftest raw-contains-key-refinement-regression-test
   (is (= {:a s/Int s/Keyword s/Any}
          (asv/refine-schema-by-contains-key {:a s/Int s/Keyword s/Any} :a true)))
   (is (= sb/Bottom
          (asv/refine-schema-by-contains-key {:a s/Int} :a false))))
