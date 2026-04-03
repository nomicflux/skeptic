(ns skeptic.analysis.normalize-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.normalize :as an]
            [skeptic.schematize :as schematize]
            [skeptic.test-examples]))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest declaration-index-contract-test
  (let [dict (schematize/ns-schemas {} 'skeptic.test-examples)
        forward-entry (an/normalize-entry (get dict 'skeptic.test-examples/forward-declared-target))
        recursive-entry (an/normalize-entry (get dict 'skeptic.test-examples/self-recursive-identity))]
    (is (= [(T s/Any)]
           (-> forward-entry :arglists (get 1) :types (->> (mapv :type)))))
    (is (= (T s/Any) (:output-type forward-entry)))
    (is (= [(T s/Any)]
           (-> recursive-entry :arglists (get 1) :types (->> (mapv :type)))))
    (is (= (T s/Any) (:output-type recursive-entry)))))
