(ns skeptic.checking.pipeline.named-fold-contract-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.bridge.render :as render]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.checking.pipeline :as pipeline]
            [skeptic.test-examples.named-fold-contract-probe])
  (:import [java.io File]))

(def fixture-file (File. "test/skeptic/test_examples/named_fold_contract_probe.clj"))
(def fixture-ns 'skeptic.test-examples.named-fold-contract-probe)
(def q #(symbol (str fixture-ns) (name %)))

(def producer-names
  ["compute-result" "compute-cache" "produce-inner-set" "add-with-cache-analogue"
   "compute-pair-cache" "cache-hit-analogue" "grow-pair-cache"
   "produce-conditional" "conditional-branch-render"
   "fn-with-call" "fn-with-composed" "fn-with-literal"])

(defn- analysis-env
  []
  (let [exprs (pipeline/ns-exprs fixture-file)
        {:keys [dict]} (pipeline/namespace-dict {} fixture-ns fixture-file)
        analyzed (pipeline/analyze-source-exprs dict fixture-ns fixture-file exprs)]
    (assoc analyzed :dict dict)))

(defn- actual-output-type
  [{:keys [analyzed]} target-sym]
  (let [target-form (some (fn [a]
                            (let [n (some-> a aapi/unwrap-with-meta)
                                  [sym _] (aapi/analyzed-def-entry fixture-ns n)]
                              (when (= sym target-sym) n)))
                          analyzed)
        init-node (some-> target-form aapi/def-init-node aapi/unwrap-with-meta)
        method (first (aapi/function-methods init-node))]
    (-> method aapi/method-result-type :output-type ato/normalize)))

(defn- rendered-actual
  [env sym]
  (render/render-type-form (actual-output-type env sym)))

(defn- resolved-def-render
  [env sym]
  (render/render-type-form (get-in env [:resolved-defs sym :type])))

(defn- public-output-result
  [target-sym]
  (some #(when (= target-sym (:enclosing-form %)) %)
        (:results (pipeline/check-ns fixture-ns fixture-file {}))))

(defn- assert-no-producer-name
  [rendered]
  (doseq [producer producer-names]
    (is (not (str/includes? (pr-str rendered) producer))
        (str producer " must not appear in rendered actual type"))))

(deftest case-a-add-with-cache-analogue-folds-threal-and-threalcache
  (let [rendered (rendered-actual (analysis-env) (q 'add-with-cache-analogue))]
    (is (= {:result 'skeptic.test-examples.named-fold-contract-probe/Threal
            :cache  'skeptic.test-examples.named-fold-contract-probe/ThrealCache}
           rendered))))

(deftest case-b-fn-with-call-folds-recursive-named-at-set
  (let [rendered (rendered-actual (analysis-env) (q 'fn-with-call))]
    (is (= {:result #{'skeptic.test-examples.named-fold-contract-probe/RecursiveNamed}}
           rendered))))

(deftest case-c-fn-with-composed-folds-recursive-named-inside-vector
  (let [rendered (rendered-actual (analysis-env) (q 'fn-with-composed))]
    (is (= {:result ['#{skeptic.test-examples.named-fold-contract-probe/RecursiveNamed}]}
           rendered))))

(deftest case-d-fn-with-literal-renders-int-no-fold
  (let [rendered (rendered-actual (analysis-env) (q 'fn-with-literal))]
    (is (= {:result [#{'Int}]}
           rendered))))

(deftest case-g-identity-preserving-flow-keeps-name
  (let [rendered (resolved-def-render (analysis-env) (q 'inferred-cache))]
    (is (= 'skeptic.test-examples.named-fold-contract-probe/FlowCache rendered))))

(deftest case-h-inferred-wrapper-keeps-named-child
  (let [rendered (resolved-def-render (analysis-env) (q 'inferred-cache-vector))]
    (is (= ['skeptic.test-examples.named-fold-contract-probe/FlowCache] rendered))))

(deftest case-i-same-structure-different-names-render-distinctly
  (let [env (analysis-env)]
    (is (= 'skeptic.test-examples.named-fold-contract-probe/Map1
           (render/render-type-form (get-in env [:dict (q 'map1-value)]))))
    (is (= 'skeptic.test-examples.named-fold-contract-probe/Map2
           (render/render-type-form (get-in env [:dict (q 'map2-value)]))))))

(deftest no-producer-names-leak-across-contract-cases
  (let [env (analysis-env)]
    (doseq [sym [(q 'add-with-cache-analogue) (q 'fn-with-call)
                 (q 'fn-with-composed) (q 'fn-with-literal)]]
      (assert-no-producer-name (rendered-actual env sym)))))

(deftest public-output-result-folds-add-with-cache-call-output
  (let [result (public-output-result (q 'visible-add-with-cache-mismatch))
        rendered (render/render-type-form (:actual-type result))]
    (is (= {:result 'skeptic.test-examples.named-fold-contract-probe/Threal
            :cache  'skeptic.test-examples.named-fold-contract-probe/ThrealCache}
           rendered))
    (assert-no-producer-name rendered)))

(deftest case-e-recursive-named-root-folds-cache-hit
  (let [result (public-output-result (q 'visible-cache-hit-mismatch))
        rendered (render/render-type-form (:actual-type result))]
    (is (= {:result 'skeptic.test-examples.named-fold-contract-probe/Threal
            :cache  'skeptic.test-examples.named-fold-contract-probe/ThrealPairCache}
           rendered))
    (assert-no-producer-name rendered)))

(deftest case-f-conditional-branches-keep-names
  (let [result (public-output-result (q 'visible-conditional-branch-mismatch))
        rendered (render/render-type-form (:actual-type result))]
    (is (= {:result (list 'conditional (q 'IntBranch) (q 'StrBranch))}
           rendered))
    (is (not= {:result '(union Int Str)} rendered))
    (is (not= {:result '(conditional Int Str)} rendered))
    (assert-no-producer-name rendered)))

(deftest empty-cache-recur-target-widens-to-named-cache
  (let [result (public-output-result (q 'recur-cache-fold-probe))]
    (is (nil? result)
        (str "expected no public recur mismatch, got: " (pr-str result)))))
