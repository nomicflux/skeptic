(ns skeptic.boundary.types-carry-provenance-test
  "Boundary enforcement: every Type produced by an ingestion path or combinator
  must carry a Provenance with a real source keyword. prov/of is the strict
  reader and throws if the invariant fails."
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.malli-spec.bridge :as mb]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.native-fns :as nf]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [some!]]))

(def ^:private tp
  (prov/make-provenance :inferred 'test-sym 'skeptic.test nil))

(defn- carries-real-prov?
  [t]
  (and (some? (prov/of t))
       (keyword? (prov/source (prov/of t)))))

(deftest schema-ingestion-attaches-prov
  (doseq [sch [s/Int s/Str s/Keyword s/Bool s/Any [s/Int]
               {:a s/Int s/Keyword s/Str}
               (s/maybe s/Int)
               (s/enum :a :b)]]
    (is (carries-real-prov? (ab/schema->type tp sch))
        (str "schema->type must carry prov for " (pr-str sch)))))

(deftest malli-ingestion-attaches-prov
  (doseq [m [:int :string :keyword :boolean :any
             [:map [:x :int]]
             [:vector :int]
             [:maybe :int]
             [:enum :a :b]]]
    (is (carries-real-prov? (mb/malli-spec->type tp m))
        (str "malli-spec->type must carry prov for " (pr-str m)))))

(deftest native-fn-output-carries-native-prov
  (let [{:keys [output-type expected-argtypes]}
        (nf/static-call-native-info clojure.lang.Numbers 'inc 1)
        output-type (some! output-type)]
    (is (carries-real-prov? output-type))
    (is (= :native (prov/source (prov/of output-type))))
    (doseq [at expected-argtypes]
      (is (carries-real-prov? at))
      (is (= :native (prov/source (prov/of at)))))))

(deftest value-inference-attaches-prov
  (doseq [v [5 3.5 "x" :k 'sym true false nil [] {} #{} '()]]
    (is (carries-real-prov? (av/type-of-value tp v))
        (str "type-of-value must carry prov for " (pr-str v)))))

(deftest exact-value-type-attaches-prov
  (doseq [v [:k 1 "s" true]]
    (is (carries-real-prov? (ato/exact-value-type tp v))
        (str "exact-value-type must carry prov for " (pr-str v)))))

(deftest combinators-preserve-prov
  (testing "av/join anchor-prov"
    (let [result (av/join tp [(ab/schema->type tp s/Int)
                              (ab/schema->type tp s/Str)])]
      (is (carries-real-prov? result))))
  (testing "amo/merge-map-types anchor-prov"
    (let [result (amo/merge-map-types tp [(ab/schema->type tp {:a s/Int})
                                           (ab/schema->type tp {:b s/Str})])]
      (is (carries-real-prov? result))))
  (testing "amoa/merge-types anchor-prov"
    (let [result (amoa/merge-types tp [(ab/schema->type tp {:a s/Int})
                                        (ab/schema->type tp {:b s/Str})])]
      (is (carries-real-prov? result))))
  (testing "combinators produce prov even on empty inputs"
    (is (carries-real-prov? (amo/merge-map-types tp [])))
    (is (carries-real-prov? (amoa/merge-types tp [])))))

