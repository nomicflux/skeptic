(ns skeptic.checking.pipeline.named-fold-contract-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.bridge.render :as render]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.test-examples.named-fold-contract-probe])
  (:import [java.io File]))

(def fixture-file (File. "test/skeptic/test_examples/named_fold_contract_probe.clj"))
(def fixture-ns 'skeptic.test-examples.named-fold-contract-probe)

(defn- actual-output-type
  [analyzed fixture-ns target-sym]
  (let [target-form (some (fn [a]
                            (let [n (some-> a aapi/unwrap-with-meta)
                                  [sym _] (aapi/analyzed-def-entry fixture-ns n)]
                              (when (= sym target-sym) n)))
                          analyzed)
        init-node (some-> target-form aapi/def-init-node aapi/unwrap-with-meta)
        method (first (aapi/function-methods init-node))]
    (-> method aapi/method-result-type :output-type ato/normalize)))

(deftest case-a-add-with-cache-analogue-folds-threal-and-threalcache
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        {:keys [analyzed]} (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)
        actual (actual-output-type analyzed fixture-ns 'skeptic.test-examples.named-fold-contract-probe/add-with-cache-analogue)
        rendered (render/render-type-form actual)]
    (is (= {:result 'skeptic.test-examples.named-fold-contract-probe/Threal
            :cache  'skeptic.test-examples.named-fold-contract-probe/ThrealCache}
           rendered))))

(deftest case-b-fn-with-call-folds-recursive-named-at-set
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        {:keys [analyzed]} (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)
        actual (actual-output-type analyzed fixture-ns 'skeptic.test-examples.named-fold-contract-probe/fn-with-call)
        rendered (render/render-type-form actual)]
    (is (= {:result #{'skeptic.test-examples.named-fold-contract-probe/RecursiveNamed}}
           rendered))))

(deftest case-c-fn-with-composed-folds-recursive-named-inside-vector
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        {:keys [analyzed]} (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)
        actual (actual-output-type analyzed fixture-ns 'skeptic.test-examples.named-fold-contract-probe/fn-with-composed)
        rendered (render/render-type-form actual)]
    (is (= {:result ['#{skeptic.test-examples.named-fold-contract-probe/RecursiveNamed}]}
           rendered))))

(deftest case-d-fn-with-literal-renders-int-no-fold
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        {:keys [analyzed]} (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)
        actual (actual-output-type analyzed fixture-ns 'skeptic.test-examples.named-fold-contract-probe/fn-with-literal)
        rendered (render/render-type-form actual)]
    (is (= {:result [#{'Int}]}
           rendered))))

(deftest no-function-names-leak-across-all-cases
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        {:keys [analyzed]} (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)
        target-syms ['skeptic.test-examples.named-fold-contract-probe/add-with-cache-analogue
                     'skeptic.test-examples.named-fold-contract-probe/fn-with-call
                     'skeptic.test-examples.named-fold-contract-probe/fn-with-composed
                     'skeptic.test-examples.named-fold-contract-probe/fn-with-literal]]
    (doseq [sym target-syms]
      (let [rendered-str (pr-str (render/render-type-form (actual-output-type analyzed fixture-ns sym)))]
        (is (not (.contains rendered-str "compute-result")))
        (is (not (.contains rendered-str "compute-cache")))
        (is (not (.contains rendered-str "produce-inner-set")))
        (is (not (.contains rendered-str "fn-with-call")))
        (is (not (.contains rendered-str "fn-with-composed")))
        (is (not (.contains rendered-str "fn-with-literal")))
        (is (not (.contains rendered-str "add-with-cache-analogue")))))))
