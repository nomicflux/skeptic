(ns skeptic.analysis-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline :as checking]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-examples.contracts :as contracts]
            [skeptic.test-helpers :refer [is-type= T tp]]
            [skeptic.test-support.shared-worker :as shared-worker])
  (:import [java.io File]))

(use-fixtures :once shared-worker/with-shared-worker)

(defn set-cache-value
  [& _args]
  nil)

(defn f
  [& _args]
  nil)

(defn int-add
  [& _args]
  nil)

(defn make-component
  [& _args]
  nil)

(defn start
  [& _args]
  nil)

;; Broad numeric annotation for native math when the checker only knows
;; "numeric, but not whether Int or non-Int numeric".
(def numeric-dyn (at/NumericDyn tp))

(defn arg-entry
  [[name schema]]
  {:type (T schema)
   :optional? false
   :name name})

(defn arity-entry
  [args]
  {:arglist (mapv first args)
   :count (count args)
   :types (mapv arg-entry args)})

(defn fn-entry
  [sym output & arities]
  (let [fn-schema (s/make-fn-schema output
                                    (mapv (fn [args]
                                            (mapv (fn [[name schema]]
                                                    (s/one schema name))
                                                  args))
                                          arities))]
    {:name (str sym)
     :type (T fn-schema)
     :output-type (T output)
     :arglists (into {}
                     (map (fn [args]
                            [(count args) (arity-entry args)]))
                     arities)}))

(def analysis-dict
  (merge (catalog/typed-test-example-entries)
         {'skeptic.analysis-test/f
          (fn-entry 'skeptic.analysis-test/f s/Any [['value s/Any]])
          'skeptic.analysis-test/int-add
          (fn-entry 'skeptic.analysis-test/int-add s/Any [['left s/Any]
                                                          ['right s/Any]])}
         {'skeptic.analysis-test/set-cache-value
          (fn-entry 'skeptic.analysis-test/set-cache-value s/Any [['value s/Any]])
          'skeptic.analysis-test/make-component
          (fn-entry 'skeptic.analysis-test/make-component s/Any [['opts s/Any]])
          'skeptic.analysis-test/start
          (fn-entry 'skeptic.analysis-test/start s/Any [['component s/Any]
                                                        ['opts s/Any]])}))

(def typed-test-examples-dict
  (catalog/typed-test-example-entries))

(def examples-file (File. "src/skeptic/examples.clj"))

(defn fixture-file-for-ns
  ^File [ns-sym]
  (or (some (fn [[_ env]]
              (when (= ns-sym (:ns env))
                (:file env)))
            catalog/fixture-envs)
      (throw (ex-info "Unknown fixture namespace"
                      {:namespace ns-sym}))))

(defn analyze-ns-file
  "Worker-backed entrypoint: analyze every top-level form of `ns-sym`'s fixture
   file on the live worker, annotate each against `dict`. Returns annotated ASTs."
  [dict ns-sym]
  (aat/analyze-ns-file dict ns-sym (fixture-file-for-ns ns-sym) {}))

(defn ast-by-name
  [asts sym]
  (aat/ast-by-name asts sym))

(defn node-by-form
  [ast form]
  (aat/node-by-form ast form))

(defn resolved-defs-of
  "Builds the `{qsym entry}` resolved-defs map (consumed by
   `aapi/resolved-def-output-type`) from worker-annotated ASTs of `ns-sym`."
  [dict ns-sym]
  (into {} (keep #(aapi/analyzed-def-entry ns-sym %)) (analyze-ns-file dict ns-sym)))

(deftest restored-resolution-contract-test
  (let [fixture-ns 'skeptic.test-examples.resolution]
    (testing "unannotated helper lookup from collected entries alone stays plain Any"
      (let [dict (catalog/typed-test-example-entries)
            asts (analyze-ns-file dict fixture-ns)
            g-ast (ast-by-name asts 'unannotated-local-helper-g)
            call-node (node-by-form g-ast '(unannotated-local-helper-f))]
        (is-type= (T s/Any) (aapi/node-type call-node))
        (is (not (at/union-type? (aapi/node-type call-node))))))

    (testing "declared helper chains use declared outputs exactly"
      (let [dict (catalog/typed-test-example-entries)
            asts (analyze-ns-file dict fixture-ns)
            resolved-defs (resolved-defs-of dict fixture-ns)
            failure-ast (ast-by-name asts 'flat-multi-step-failure)
            call-node (node-by-form failure-ast '(flat-multi-step-takes-str (flat-multi-step-g)))]
        (is-type= (T s/Int) (aapi/resolved-def-output-type resolved-defs 'skeptic.test-examples.resolution/flat-multi-step-f))
        (is-type= (T s/Int) (aapi/resolved-def-output-type resolved-defs 'skeptic.test-examples.resolution/flat-multi-step-g))
        (let [args (aapi/call-actual-argtypes call-node)]
          (is (= 1 (count args)))
          (is-type= (T s/Int) (first args)))
        (is (not (at/union-type? (aapi/resolved-def-output-type resolved-defs
                                                                'skeptic.test-examples.resolution/flat-multi-step-g))))))))

(defn- worker-opts
  []
  {:worker-conn oracle/*worker-conn*})

(defn- project-state-of
  [ns-sym]
  (checking/project-state (worker-opts)
                          {ns-sym (fixture-file-for-ns ns-sym)}))

(defn- accessor-summaries-of
  [ns-sym]
  (:accessor-summaries (project-state-of ns-sym)))

(deftest accessor-helper-resolution-contract-test
  (let [contracts-ns 'skeptic.test-examples.contracts
        nullability-ns 'skeptic.test-examples.nullability]
    (testing "accessor summaries are emitted only for supported unary projection helpers"
      (let [summaries (accessor-summaries-of contracts-ns)
            choose-summary (get summaries 'skeptic.test-examples.contracts/choose)]
        (is (= {:kind :unary-map-projection :path [{:value :k}]}
               (get summaries 'skeptic.test-examples.contracts/vtype)))
        (is (= :unary-map-projection (:kind choose-summary)))
        (is (= [:k] (mapv :value (:path choose-summary))))
        (is (= :a (:default choose-summary)))
        (is (= :keyword (:result-transform choose-summary)))
        (is (= #{:a :b} (set (:values choose-summary)))))
      (is (nil? (get (accessor-summaries-of nullability-ns)
                     'skeptic.test-examples.nullability/non-null-transform)))))

  (testing "prepass exposes helper accessor summaries to later case narrowing"
    (let [dict (catalog/typed-test-example-entries)
          fixture-ns 'skeptic.test-examples.contracts
          accessor-summaries (accessor-summaries-of fixture-ns)
          asts (aat/analyze-ns-file dict fixture-ns (fixture-file-for-ns fixture-ns)
                                    {:accessor-summaries accessor-summaries})
          ast (ast-by-name asts 'conditional-dispatch-success)
          handle-a (aapi/find-node ast #(and (aapi/call-node? %)
                                             (= '(handle-a v) (aapi/node-form %))))
          handle-b (aapi/find-node ast #(and (aapi/call-node? %)
                                             (= '(handle-b v) (aapi/node-form %))))]
      (is-type= (T contracts/VariantA) (first (aapi/call-actual-argtypes handle-a)))
      (is-type= (T contracts/VariantB) (first (aapi/call-actual-argtypes handle-b)))))

  (testing "classifier case narrowing crosses helper function boundaries"
    (let [fixture-ns 'skeptic.test-examples.contracts
          project-state (project-state-of fixture-ns)
          accessor-summaries (:accessor-summaries project-state)
          dict (:dict project-state)
          asts (aat/analyze-ns-file dict fixture-ns (fixture-file-for-ns fixture-ns)
                                    {:accessor-summaries accessor-summaries})
          ast (ast-by-name asts 'chooses-conditional-success)
          f-a (aapi/find-node ast #(and (aapi/call-node? %)
                                        (= '(f-a x) (aapi/node-form %))))
          f-b (aapi/find-node ast #(and (aapi/call-node? %)
                                        (= '(f-b x) (aapi/node-form %))))]
      (is-type= (T contracts/A) (first (aapi/call-actual-argtypes f-a)))
      (is-type= (T contracts/B) (first (aapi/call-actual-argtypes f-b))))))
