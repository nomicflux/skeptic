(ns skeptic.analysis-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.types :as at]
            [skeptic.checking.pipeline :as checking]
            [skeptic.source :as source]
            [skeptic.test-examples.catalog :as catalog])
  (:import [java.io File]))

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

(def x nil)
(def y nil)
(def z nil)
(def cache nil)

(defn T
  [schema]
  (ab/schema->type schema))

;; Broad numeric annotation for native math when the checker only knows
;; "numeric, but not whether Int or non-Int numeric".
(def numeric-dyn at/NumericDyn)

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

(def sample-dict
  {'f
   {:name "f"
    :type (T (s/=> s/Int s/Int))
    :output-type (T s/Int)
    :arglists {1 {:arglist ['x]
                   :count 1
                   :types [{:type (T s/Int) :optional? false :name 'x}]}
               2 {:arglist ['y 'z]
                  :count 2
                  :types [{:type (T s/Str) :optional? false :name 'y}
                          {:type (T s/Int) :optional? false :name 'z}]}}}})

(def examples-file (File. "src/skeptic/examples.clj"))

(defn fixture-file
  ^File [sym]
  (:file (catalog/owner-of sym)))

(defn fixture-file-for-ns
  ^File [ns-sym]
  (or (some (fn [[_ env]]
              (when (= ns-sym (:ns env))
                (:file env)))
            catalog/fixture-envs)
      (throw (ex-info "Unknown fixture namespace"
                      {:namespace ns-sym}))))

(defn locals
  [& syms]
  {:locals (into {}
                 (map (fn [sym] [sym (T s/Any)]))
                 syms)})

(defn local-types
  [m]
  {:locals m})

(defn analyze-form
  ([form]
   (aat/annotate-form-loop analysis-dict form {:ns 'skeptic.analysis-test}))
  ([arg1 arg2]
   (if (map? arg1)
     (aat/annotate-form-loop arg1 arg2 {:ns 'skeptic.analysis-test})
     (aat/annotate-form-loop analysis-dict arg1 (merge {:ns 'skeptic.analysis-test}
                                                       arg2))))
  ([dict form opts]
   (aat/annotate-form-loop dict form (merge {:ns 'skeptic.analysis-test}
                                            opts))))

(def stable-keys aat/stable-keys)

(defn arglist-types
  [root arity]
  (aat/arglist-types root arity))

(defn project-ast
  [root]
  (aat/project-ast root))

(defn projected-nodes
  [root]
  (aat/projected-nodes root))

(defn find-projected-node
  [root pred]
  (aat/find-projected-node root pred))

(defn child-projection
  [node key]
  (aat/child-projection node key))

(defn ast-by-name
  [asts sym]
  (aat/ast-by-name asts sym))

(defn node-by-form
  [ast form]
  (aat/node-by-form ast form))

(defmacro source-exprs-in
  [ns-sym file]
  `(checking/block-in-ns ~ns-sym ~file
                         (checking/ns-exprs ~file)))

(deftest restored-resolution-contract-test
  (testing "unannotated helper lookup from collected entries alone stays plain Any"
    (let [dict (catalog/typed-test-example-entries)
          form (->> 'skeptic.test-examples.resolution/unannotated-local-helper-g
                    (source/get-fn-code {})
                    read-string)
          ast (aat/annotate-form-loop dict form {:ns 'skeptic.test-examples.resolution})
          call-node (node-by-form ast '(unannotated-local-helper-f))]
      (is (= (T s/Any) (aapi/node-type call-node)))
      (is (not (at/union-type? (aapi/node-type call-node))))))

  (testing "declared helper chains use declared outputs exactly"
    (let [dict (catalog/typed-test-example-entries)
          fixture-ns 'skeptic.test-examples.resolution
          fixture-file (fixture-file-for-ns fixture-ns)
          {:keys [resolved resolved-defs]} (checking/analyze-source-exprs dict
                                                                         fixture-ns
                                                                         fixture-file
                                                                         (source-exprs-in fixture-ns fixture-file))
          failure-ast (ast-by-name resolved 'flat-multi-step-failure)
          call-node (node-by-form failure-ast '(flat-multi-step-takes-str (flat-multi-step-g)))]
      (is (= (T s/Int) (aapi/resolved-def-output-type resolved-defs 'skeptic.test-examples.resolution/flat-multi-step-f)))
      (is (= (T s/Int) (aapi/resolved-def-output-type resolved-defs 'skeptic.test-examples.resolution/flat-multi-step-g)))
      (is (= [(T s/Int)] (aapi/call-actual-argtypes call-node)))
      (is (not (at/union-type? (aapi/resolved-def-output-type resolved-defs
                                                              'skeptic.test-examples.resolution/flat-multi-step-g)))))))

(deftest accessor-helper-resolution-contract-test
  (let [dict (catalog/typed-test-example-entries)
        contracts-ns 'skeptic.test-examples.contracts
        contracts-file (fixture-file-for-ns contracts-ns)
        nullability-ns 'skeptic.test-examples.nullability
        nullability-file (fixture-file-for-ns nullability-ns)]
    (testing "accessor summaries are emitted only for trivial unary accessors"
      (is (= {:kind :unary-map-accessor :kw :k}
             (get (:accessor-summaries (checking/analyze-source-exprs dict
                                                                      contracts-ns
                                                                      contracts-file
                                                                      (source-exprs-in contracts-ns contracts-file)))
                  'skeptic.test-examples.contracts/vtype)))
      (is (nil? (get (:accessor-summaries (checking/analyze-source-exprs dict
                                                                         nullability-ns
                                                                         nullability-file
                                                                         (source-exprs-in nullability-ns nullability-file)))
                     'skeptic.test-examples.nullability/non-null-transform)))))

  (testing "prepass exposes helper accessor summaries to later case narrowing"
    (let [dict (catalog/typed-test-example-entries)
          fixture-ns 'skeptic.test-examples.contracts
          fixture-file (fixture-file-for-ns fixture-ns)
          exprs (source-exprs-in fixture-ns fixture-file)
          {:keys [accessor-summaries]} (checking/analyze-source-exprs dict
                                                                      fixture-ns
                                                                      fixture-file
                                                                      exprs)
          form (->> 'skeptic.test-examples.contracts/conditional-dispatch-success
                    (source/get-fn-code {})
                    read-string)
          ast (first (:resolved (checking/analyze-source-exprs dict
                                                               fixture-ns
                                                               fixture-file
                                                               [form]
                                                               {:accessor-summaries accessor-summaries})))
          handle-a (aapi/find-node ast #(and (aapi/call-node? %)
                                             (= '(handle-a v) (aapi/node-form %))))
          handle-b (aapi/find-node ast #(and (aapi/call-node? %)
                                             (= '(handle-b v) (aapi/node-form %))))]
      (is (= (T {:x s/Int}) (first (aapi/call-actual-argtypes handle-a))))
      (is (= (T {:y s/Str}) (first (aapi/call-actual-argtypes handle-b)))))))
