(ns skeptic.checking.pipeline.named-fold-diagnostic-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.bridge.render :as render]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.provenance :as prov]
            [skeptic.test-examples.form-refs])
  (:import [java.io File]))

(def fixture-file (File. "test/skeptic/test_examples/form_refs.clj"))
(def fixture-ns 'skeptic.test-examples.form-refs)
(def declared-fn-sym 'skeptic.test-examples.form-refs/fn-with-named-map-ann)
(def call-fn-sym 'skeptic.test-examples.form-refs/fn-with-call-results)
(def composed-fn-sym 'skeptic.test-examples.form-refs/fn-with-composed-body)
(def expected-qsym 'skeptic.test-examples.form-refs/RecursiveNamed)

(defn- value-type-for
  [output-map kw]
  (some (fn [[k v]] (when (and (at/value-type? k) (= (:value k) kw)) v))
        (:entries output-map)))

(deftest diagnostic-declared-side-folds
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        fn-type (get dict declared-fn-sym)
        method (first (:methods fn-type))
        output-map (:output method)
        result-value-type (value-type-for output-map :result)
        result-prov (some-> result-value-type prov/of)]
    (testing "DECLARED side: admission produces qualified prov on value-Type"
      (is (= :schema (:source result-prov))
          (str "actual prov=" result-prov))
      (is (= expected-qsym (:qualified-sym result-prov))
          (str "actual prov=" result-prov)))
    (testing "DECLARED side: render folds non-root value to qualified-sym"
      (is (= expected-qsym (get (render/render-type-form output-map) :result))))))

(defn- analyzed-fn-output
  [analyzed fixture-ns target-sym]
  (let [target-form (some (fn [a]
                            (let [n (some-> a aapi/unwrap-with-meta)
                                  [sym _] (aapi/analyzed-def-entry fixture-ns n)]
                              (when (= sym target-sym) n)))
                          analyzed)
        init-node (some-> target-form aapi/def-init-node aapi/unwrap-with-meta)
        method (first (aapi/function-methods init-node))]
    {:target-form target-form
     :method method
     :actual-output-raw (:output-type (aapi/method-result-type method))}))

(deftest diagnostic-actual-side-loss-point
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        {:keys [analyzed]} (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)
        target-form (some (fn [a]
                            (let [n (some-> a aapi/unwrap-with-meta)
                                  [sym _] (aapi/analyzed-def-entry fixture-ns n)]
                              (when (= sym call-fn-sym) n)))
                          analyzed)
        init-node (some-> target-form aapi/def-init-node aapi/unwrap-with-meta)
        method (first (aapi/function-methods init-node))
        actual-output-raw (:output-type (aapi/method-result-type method))
        actual-output (ato/normalize actual-output-raw)
        result-value-type (value-type-for actual-output :result)
        result-prov (some-> result-value-type prov/of)
        rendered (render/render-type-form actual-output)]
    (testing "structure exists"
      (is (some? target-form) "target def node found")
      (is (some? init-node) "init (fn) node found")
      (is (at/map-type? actual-output)
          (str "expected MapT; actual=" actual-output)))
    (testing "ACTUAL side: prov on value-Type for :result"
      (is (some? result-value-type)
          (str "no value-Type for :result; entries=" (:entries actual-output)))
      (is (= :schema (:source result-prov))
          (str "LOSS POINT: expected :schema source on inferred call-result; actual prov=" result-prov))
      (is (= expected-qsym (:qualified-sym result-prov))
          (str "actual prov=" result-prov)))
    (testing "ACTUAL side: render folds"
      (is (= expected-qsym (get rendered :result))
          (str "rendered=" rendered)))))

(defn- collect-refs-deep
  [prov]
  (lazy-seq
   (cons prov
         (mapcat collect-refs-deep (:refs prov)))))

(deftest diagnostic-composed-body-loss-point
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        {:keys [analyzed]} (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)
        {:keys [actual-output-raw]} (analyzed-fn-output analyzed fixture-ns composed-fn-sym)
        actual-output (ato/normalize actual-output-raw)
        result-value-type (value-type-for actual-output :result)
        result-prov (some-> result-value-type prov/of)
        produce-inner-set-sym 'skeptic.test-examples.form-refs/produce-inner-set]
    (testing "COMPOSED side: inferred VectorT records its constituents in :refs"
      (is (some? result-value-type)
          (str "no value-Type for :result; entries=" (:entries actual-output)))
      (is (seq (:refs result-prov))
          (str ":refs is empty; actual prov=" result-prov))
      (is (some (fn [p]
                  (= produce-inner-set-sym (:qualified-sym p)))
                (collect-refs-deep result-prov))
          (str "no ref reaches produce-inner-set; refs="
               (mapv :qualified-sym (collect-refs-deep result-prov)))))))
