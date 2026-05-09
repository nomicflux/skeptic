(ns skeptic.schema.collect.cljs-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.types :as at]
            [skeptic.cljs.compiler-env :as compiler-env]
            [skeptic.provenance :as prov]
            [skeptic.schema.collect.cljs :as sut]))

(def ^:private fixture-path "dev-resources/cljs-fixtures/p4.cljs")

(def ^:private p (prov/make-provenance :schema 'p4/anon 'p4 nil))

(def ^:private ^:dynamic *result* nil)

(defn- collect-once
  [f]
  (require 'schema.core)
  (let [cenv (compiler-env/fresh-state)
        asts (compiler-env/load-source! cenv fixture-path)
        result (sut/ns-schema-results-cljs cenv fixture-path 'p4 asts)]
    (binding [*result* result] (f))))

(use-fixtures :once collect-once)

(defn- entry-type
  [qsym]
  (-> *result* :entries (get qsym) :schema (->> (ab/schema->type p))))

(defn- expected-type
  [schema]
  (ab/schema->type p schema))

(deftest p4-cljs-admission-no-errors
  (is (empty? (:errors *result*))))

(deftest p4-cljs-admits-all-six-vars
  (is (= #{'p4/my-int 'p4/MySchema 'p4/f 'p4/g 'p4/h 'p4/k}
         (set (keys (:entries *result*))))))

(deftest p4-cljs-admits-s-def
  (is (at/type=? (expected-type s/Int) (entry-type 'p4/my-int))))

(deftest p4-cljs-admits-s-defschema
  (is (at/type=? (expected-type {:a s/Int}) (entry-type 'p4/MySchema))))

(deftest p4-cljs-admits-s-defn
  (is (at/type=? (expected-type (s/->FnSchema s/Int [[(s/one s/Int 'x)]]))
                 (entry-type 'p4/f))))

(deftest p4-cljs-admits-s-defn-no-return
  (is (at/type=? (expected-type (s/->FnSchema s/Any [[(s/one s/Int 'x)]]))
                 (entry-type 'p4/g))))

(deftest p4-cljs-admits-s-defn-multi-input
  (is (at/type=? (expected-type
                  (s/->FnSchema s/Str [[(s/one s/Int 'x) (s/one s/Int 'y)]]))
                 (entry-type 'p4/h))))

(deftest p4-cljs-admits-s-defn-variadic
  (is (at/type=? (expected-type
                  (s/->FnSchema s/Int [[(s/one s/Int 'x) s/Int]]))
                 (entry-type 'p4/k))))
