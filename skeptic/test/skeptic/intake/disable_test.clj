(ns skeptic.intake.disable-test
  "Cross-stream regression: --plumatic-disable / --malli-disable wholly empty
  their respective intake. The other stream is unaffected; a Var declared via
  both streams survives via the enabled one; both flags together leave only
  native-fn entries."
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.provenance :as prov]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.typed-decls.malli :as typed-decls.malli])
  (:import [java.io File]))

(def fixture-ns 'skeptic.research.intake-combined-fixture)
(def fixture-file (File. "test/skeptic/research/intake_combined_fixture.clj"))

(defn- qsym [name-str]
  (symbol "skeptic.research.intake-combined-fixture" name-str))

(def plumatic-only-syms
  (map qsym ["aliased-defn" "qualified-defn" "schemy-defn" "aliased-def"]))

(def malli-only-syms
  (map qsym ["malli-arrow" "malli-mx" "malli-meta-only"]))

(def cross-stream-sym (qsym "cross-stream"))

(deftest plumatic-disable-empties-plumatic-stream
  (require fixture-ns)
  (let [res (typed-decls/typed-ns-results
             {:plumatic-disable true}
             fixture-ns :clj fixture-file nil)]
    (testing "Plumatic stream produces no dict / provenance / errors"
      (is (= {} (:dict res)))
      (is (= {} (:provenance res)))
      (is (= [] (:errors res)))
      (is (= #{} (:ignore-body res))))
    (testing "Malli stream still works under the same flag"
      (let [malli-res (typed-decls.malli/typed-ns-malli-results
                       {:plumatic-disable true} fixture-ns :clj)]
        (is (seq (:dict malli-res)))
        (is (contains? (:dict malli-res) (qsym "malli-arrow")))))))

(deftest malli-disable-empties-malli-stream
  (require fixture-ns)
  (let [res (typed-decls.malli/typed-ns-malli-results
             {:malli-disable true} fixture-ns :clj)]
    (testing "Malli stream produces no dict / provenance / errors"
      (is (= {} (:dict res)))
      (is (= {} (:provenance res)))
      (is (= [] (:errors res))))
    (testing "Plumatic stream still works under the same flag"
      (let [schema-res (typed-decls/typed-ns-results
                        {:malli-disable true}
                        fixture-ns :clj fixture-file nil)]
        (is (seq (:dict schema-res)))
        (is (contains? (:dict schema-res) (qsym "aliased-defn")))))))

(deftest plumatic-disable-removes-plumatic-qsyms-from-merged-dict
  (require fixture-ns)
  (let [{:keys [dict per-ns]} (pipeline/project-state
                                {:plumatic-disable true}
                                {fixture-ns fixture-file})
        ns-provs (get-in per-ns [fixture-ns :provenance])]
    (testing "Plumatic-only qsyms absent from merged dict"
      (doseq [sym plumatic-only-syms]
        (is (not (contains? dict sym))
            (str sym " should not be admitted under :plumatic-disable"))))
    (testing "Malli-only qsyms still present"
      (doseq [sym malli-only-syms]
        (is (contains? dict sym)
            (str sym " should still be admitted via Malli"))))
    (testing "cross-stream Var survives via Malli"
      (is (contains? dict cross-stream-sym))
      (is (= :malli (prov/source (get ns-provs cross-stream-sym)))
          "cross-stream prov should be :malli when Plumatic is disabled"))))

(deftest malli-disable-removes-malli-qsyms-from-merged-dict
  (require fixture-ns)
  (let [{:keys [dict per-ns]} (pipeline/project-state
                                {:malli-disable true}
                                {fixture-ns fixture-file})
        ns-provs (get-in per-ns [fixture-ns :provenance])]
    (testing "Malli-only qsyms absent from merged dict"
      (doseq [sym malli-only-syms]
        (is (not (contains? dict sym))
            (str sym " should not be admitted under :malli-disable"))))
    (testing "Plumatic-only qsyms still present"
      (doseq [sym plumatic-only-syms]
        (is (contains? dict sym)
            (str sym " should still be admitted via Plumatic"))))
    (testing "cross-stream Var survives via Plumatic"
      (is (contains? dict cross-stream-sym))
      (is (= :schema (prov/source (get ns-provs cross-stream-sym)))
          "cross-stream prov should be :schema when Malli is disabled"))))

(deftest both-disabled-leaves-only-native-entries
  (require fixture-ns)
  (let [{:keys [dict]} (pipeline/project-state
                         {:plumatic-disable true
                          :malli-disable true}
                         {fixture-ns fixture-file})]
    (doseq [sym (concat plumatic-only-syms malli-only-syms [cross-stream-sym])]
      (is (not (contains? dict sym))
          (str sym " should be absent when both intakes are disabled")))))
