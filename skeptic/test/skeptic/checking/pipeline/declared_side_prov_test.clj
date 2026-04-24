(ns skeptic.checking.pipeline.declared-side-prov-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.provenance :as prov]
            [skeptic.test-examples.named-fold-contract-probe])
  (:import [java.io File]))

(def fixture-file (File. "test/skeptic/test_examples/named_fold_contract_probe.clj"))
(def fixture-ns 'skeptic.test-examples.named-fold-contract-probe)

(defn- declared-prov
  [dict qualified-sym]
  (prov/of (get dict qualified-sym)))

(deftest x-anchors-to-foo-var-prov
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        p (declared-prov dict 'skeptic.test-examples.named-fold-contract-probe/x)]
    (is (= :schema (prov/source p)))
    (is (= 'skeptic.test-examples.named-fold-contract-probe/Foo (:qualified-sym p)))))

(deftest y-anchors-to-bar-var-prov-not-foo
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        p (declared-prov dict 'skeptic.test-examples.named-fold-contract-probe/y)]
    (is (= :schema (prov/source p)))
    (is (= 'skeptic.test-examples.named-fold-contract-probe/Bar (:qualified-sym p)))))

(deftest z-anchors-to-inline-named-label
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        p (declared-prov dict 'skeptic.test-examples.named-fold-contract-probe/z)]
    (is (= :schema (prov/source p)))
    (is (= 'Foo (:qualified-sym p)))
    (is (= 'skeptic.test-examples.named-fold-contract-probe (:declared-in p)))))

(deftest w-anchors-to-myint-alias-var-prov
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        p (declared-prov dict 'skeptic.test-examples.named-fold-contract-probe/w)]
    (is (= :schema (prov/source p)))
    (is (= 'skeptic.test-examples.named-fold-contract-probe/MyInt (:qualified-sym p)))))

(deftest q-keeps-declared-var-prov-for-core-schema-int
  (let [{:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        p (declared-prov dict 'skeptic.test-examples.named-fold-contract-probe/q)]
    (is (= :schema (prov/source p)))
    (is (= 'skeptic.test-examples.named-fold-contract-probe/q (:qualified-sym p)))))
