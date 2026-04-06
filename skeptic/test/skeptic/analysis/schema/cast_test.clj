 (ns skeptic.analysis.schema.cast-test
   (:require [clojure.test :refer [deftest is]]
             [schema.core :as s]
             [skeptic.analysis.schema.cast :as as]))

 (deftest raw-schema-check-cast-adapts-to-type-domain-test
   (let [exact (as/check-cast s/Int s/Int)
         target-dyn (as/check-cast s/Int s/Any)
         map-cast (as/check-cast {:a s/Any} {:a s/Int})]
     (is (:ok? exact))
     (is (= :exact (:rule exact)))
     (is (:ok? target-dyn))
     (is (= :target-dyn (:rule target-dyn)))
     (is (:ok? map-cast))
     (is (= :map (:rule map-cast)))))
