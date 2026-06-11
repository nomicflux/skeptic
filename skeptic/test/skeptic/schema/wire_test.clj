(ns skeptic.schema.wire-test
  "Host-side decode of worker-encoded Plumatic schema values. Pins the totality
   contract: records outside the decode vocabulary (s/protocol, project-defined
   Schema implementations) admit as named Any instead of throwing, and predicate
   names lose the compiler's __NNNN counter (map?--4367 -> map?)."
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.schema.wire :as wire]))

(deftest unknown-record-decodes-to-named-any
  (let [decoded (wire/decode-schema
                 {:tag :record
                  :class "schema.core.Protocol"
                  :fields {:p {:tag :var-ref :qualified-sym 'schema.core/Schema}}})]
    (is (= (s/named s/Any 'schema.core.Protocol) decoded))))

(deftest project-defined-record-decodes-to-named-any
  (let [decoded (wire/decode-schema
                 {:tag :record
                  :class "schema.experimental.abstract_map.AbstractSchema"
                  :fields {}})]
    (is (= (s/named s/Any 'schema.experimental.abstract_map.AbstractSchema)
           decoded))))

(deftest optional-one-decodes-via-s-optional
  ;; rest-args declare as (s/optional T name): One with :optional? true.
  ;; s/one is strictly 2-arity, so the optional? branch must build through
  ;; s/optional.
  (let [decoded (wire/decode-schema
                 {:tag :record
                  :class "schema.core.One"
                  :fields {:schema {:tag :record
                                    :class "schema.core.AnythingSchema"
                                    :fields {}}
                           :optional? {:tag :literal :value true}
                           :name {:tag :literal :value 'rest0}}})]
    (is (= (s/optional s/Any 'rest0) decoded)))
  (let [decoded (wire/decode-schema
                 {:tag :record
                  :class "schema.core.One"
                  :fields {:schema {:tag :record
                                    :class "schema.core.AnythingSchema"
                                    :fields {}}
                           :optional? {:tag :literal :value false}
                           :name {:tag :literal :value 'arg0}}})]
    (is (= (s/one s/Any 'arg0) decoded))))

(deftest predicate-pred-name-demunged
  (let [decoded (wire/decode-schema
                 {:tag :record
                  :class "schema.core.Predicate"
                  :fields {:p? {:tag :fn :sym 'clojure.core/even?}
                           :pred-name {:tag :literal :value 'even?--4367}}})]
    (is (= 'even? (:pred-name decoded)))
    (is (ifn? (:p? decoded)))))
