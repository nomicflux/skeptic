(ns skeptic.test-examples.catalog
  (:require [skeptic.test-examples.basics]
            [skeptic.test-examples.collections]
            [skeptic.test-examples.control-flow]
            [skeptic.test-examples.contracts]
            [skeptic.test-examples.fixture-flags]
            [skeptic.test-examples.malli-contracts]
            [skeptic.test-examples.nullability]
            [skeptic.test-examples.resolution]
            [skeptic.typed-decls :as typed-decls])
  (:import [java.io File]))

(def schema-fixture-order
  [:basics
   :control-flow
   :collections
   :resolution
   :contracts
   :nullability
   :fixture-flags])

(def malli-fixture-order
  [:malli-contracts])

(def fixture-envs
  {:basics {:ns 'skeptic.test-examples.basics
            :file (File. "test/skeptic/test_examples/basics.clj")}
   :control-flow {:ns 'skeptic.test-examples.control-flow
                  :file (File. "test/skeptic/test_examples/control_flow.clj")}
   :collections {:ns 'skeptic.test-examples.collections
                 :file (File. "test/skeptic/test_examples/collections.clj")}
   :resolution {:ns 'skeptic.test-examples.resolution
                :file (File. "test/skeptic/test_examples/resolution.clj")}
   :contracts {:ns 'skeptic.test-examples.contracts
               :file (File. "test/skeptic/test_examples/contracts.clj")}
   :nullability {:ns 'skeptic.test-examples.nullability
                 :file (File. "test/skeptic/test_examples/nullability.clj")}
   :fixture-flags {:ns 'skeptic.test-examples.fixture-flags
                   :file (File. "test/skeptic/test_examples/fixture_flags.clj")}
   :malli-contracts {:ns 'skeptic.test-examples.malli-contracts
                     :file (File. "test/skeptic/test_examples/malli_contracts.clj")}})

(def documented-canary-symbols
  ['skeptic.test-examples.basics/sample-unannotated-fn
   'skeptic.test-examples.basics/sample-fully-annotated-fn
   'skeptic.test-examples.basics/sample-arg-annotated-fn
   'skeptic.test-examples.control-flow/sample-let-fn
   'skeptic.test-examples.control-flow/sample-if-fn
   'skeptic.test-examples.control-flow/sample-if-mixed-fn
   'skeptic.test-examples.control-flow/sample-do-fn
   'skeptic.test-examples.control-flow/sample-try-catch-fn
   'skeptic.test-examples.control-flow/sample-try-finally-fn
   'skeptic.test-examples.control-flow/sample-try-catch-finally-fn
   'skeptic.test-examples.control-flow/sample-throw-fn
   'skeptic.test-examples.resolution/sample-fn-fn
   'skeptic.test-examples.resolution/sample-var-fn-fn
   'skeptic.test-examples.resolution/sample-found-var-fn-fn
   'skeptic.test-examples.resolution/sample-missing-var-fn-fn
   'skeptic.test-examples.resolution/sample-namespaced-keyword-fn
   'skeptic.test-examples.resolution/sample-let-fn-fn
   'skeptic.test-examples.resolution/sample-functional-fn
   'skeptic.test-examples.collections/map-literal-input-success
   'skeptic.test-examples.collections/map-var-input-success
   'skeptic.test-examples.collections/map-unannotated-fn-input-success
   'skeptic.test-examples.collections/simple-map-output-success
   'skeptic.test-examples.collections/vec-literal-input-success
   'skeptic.test-examples.control-flow/loop-sum-success
   'skeptic.test-examples.control-flow/loop-returns-int-vec-literal
   'skeptic.test-examples.control-flow/loop-returns-nested-schema-map
   'skeptic.test-examples.control-flow/loop-recur-accumulates-int-vec
   'skeptic.test-examples.control-flow/loop-recur-nested-schema-map
   'skeptic.test-examples.control-flow/for-first-int-success
   'skeptic.test-examples.control-flow/for-even-str-odd-int-declared-cond-pre-seq
   'skeptic.test-examples.contracts/narrowing-string-predicate-success
   'skeptic.test-examples.contracts/narrowing-keyword-invoke-presence-success
   'skeptic.test-examples.contracts/narrowing-case-success
   'skeptic.test-examples.contracts/narrowing-assoc-get-success
   'skeptic.test-examples.contracts/narrowing-fn-qmark-success
   'skeptic.test-examples.contracts/fn-type-satisfies-pred-fn-success
   'skeptic.test-examples.nullability/nil-satisfies-maybe-constrained-success
   'skeptic.test-examples.collections/format-hello-map-success
   'skeptic.test-examples.nullability/test-eq-nil
   'skeptic.test-examples.nullability/take-val
   'skeptic.test-examples.nullability/process-val
   'skeptic.test-examples.nullability/multi-step-some->-success
   'skeptic.test-examples.nullability/when-truthy-nil-local-success
   'skeptic.test-examples.nullability/when-and-some?-nil-success
   'skeptic.test-examples.nullability/when-and-some?-and-nil-success
   'skeptic.test-examples.nullability/when-and-some?-multi-nil-success
   'skeptic.test-examples.nullability/when-not-throw-nil-local-success
   'skeptic.test-examples.nullability/guarded-keys-caller
   'skeptic.test-examples.basics/regex-return-caller
   'skeptic.test-examples.collections/abcde-maps
   'skeptic.test-examples.collections/a-dissoc
   'skeptic.test-examples.nullability/some-to-lambda-success
   'skeptic.test-examples.nullability/eq-nil-return-success
   'skeptic.test-examples.basics/closure-param-type-fn-success
   'skeptic.test-examples.collections/field-in-thread-success
   'skeptic.test-examples.collections/maybe-target-success
   'skeptic.test-examples.basics/fn-chain-success])

(def ^:private symbol-owner-map
  (delay
    (reduce (fn [acc category]
              (let [{ns-sym :ns} (get fixture-envs category)]
                (reduce (fn [m public-sym]
                          (assoc m (symbol (str ns-sym) (name public-sym)) category))
                        acc
                        (keys (ns-publics ns-sym)))))
            {}
            (concat schema-fixture-order malli-fixture-order))))

(defn owner-of
  [sym]
  (when-let [category (get @symbol-owner-map sym)]
    (assoc (get fixture-envs category) :category category)))

(def ^:private typed-entry-map
  (delay
    (reduce (fn [acc category]
              (let [{ns-sym :ns} (get fixture-envs category)
                    {entries :dict} (typed-decls/typed-ns-results {} ns-sym)]
                (merge acc entries)))
            {}
            schema-fixture-order)))

(defn typed-test-example-entries
  []
  @typed-entry-map)
