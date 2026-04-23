(ns skeptic.checking.pipeline.declared-input-prov-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.provenance :as prov]
            [skeptic.test-examples.named-fold-contract-probe])
  (:import [java.io File]))

(def fixture-file (File. "test/skeptic/test_examples/named_fold_contract_probe.clj"))
(def fixture-ns 'skeptic.test-examples.named-fold-contract-probe)

(defn- method-for-arity
  [fn-type arity]
  (some #(when (= arity (:min-arity %)) %) (:methods fn-type)))

(defn- input-qsyms
  [fn-type arity]
  (mapv #(:qualified-sym (prov/of %)) (:inputs (method-for-arity fn-type arity))))

(deftest fixed-arity-input-provs-anchor-at-annotation-site
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        probe (get dict 'skeptic.test-examples.named-fold-contract-probe/add-with-cache-input-probe)]
    (is (= ['skeptic.test-examples.named-fold-contract-probe/Threal
            'skeptic.test-examples.named-fold-contract-probe/Threal
            'skeptic.test-examples.named-fold-contract-probe/ThrealCache]
           (input-qsyms probe 3)))))

(deftest varargs-input-prov-anchors-at-annotation-site
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        probe (get dict 'skeptic.test-examples.named-fold-contract-probe/varargs-input-probe)
        qsyms (input-qsyms probe 2)]
    (is (= 'skeptic.test-examples.named-fold-contract-probe/Threal (first qsyms)))))
