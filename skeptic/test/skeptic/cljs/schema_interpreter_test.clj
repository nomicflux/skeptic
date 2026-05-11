(ns skeptic.cljs.schema-interpreter-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.cljs.schema-interpreter :as si]))

(deftest primitive-resolves-to-schema-var
  (is (identical? s/Int (si/interpret-schema-form 'schema.core/Int))))

(deftest maybe-constructs-real-record
  (let [result (si/interpret-schema-form '(schema.core/maybe schema.core/Int))]
    (is (instance? schema.core.Maybe result))
    (is (identical? s/Int (.-schema ^schema.core.Maybe result)))))

(deftest map-literal-walks
  (let [result (si/interpret-schema-form '{:a schema.core/Int :b schema.core/Str})]
    (is (= {:a s/Int :b s/Str} result))))

(deftest vector-literal-walks
  (let [result (si/interpret-schema-form '[schema.core/Int])]
    (is (= [s/Int] result))))

(deftest set-literal-walks
  (let [result (si/interpret-schema-form '#{schema.core/Int})]
    (is (= #{s/Int} result))))

(deftest fn-schema-constructor-builds-record
  (let [result (si/interpret-schema-form
                '(schema.core/->FnSchema schema.core/Int [[schema.core/Int]]))]
    (is (instance? schema.core.FnSchema result))))

(deftest one-constructor-builds-record
  (let [result (si/interpret-schema-form
                '(schema.core/one schema.core/Int (quote x)))]
    (is (instance? schema.core.One result))))

(deftest unallowlisted-namespace-throws
  (is (thrown? Exception
               (si/interpret-schema-form '(clojure.java.io/file "/tmp/x")))))
