(ns skeptic.analysis.calls-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.call-kinds.invoke-output :as ck-invoke-output]
            [skeptic.analysis.call-kinds.static-output :as ck-static]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis-test :as atst]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-support.admit :as admit]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.test-helpers :refer [is-type= T tp]]
            [skeptic.test-support.shared-worker :as shared-worker]
            [skeptic.static-call-examples])
  (:import [java.io File]))

(use-fixtures :once shared-worker/with-shared-worker)

(def static-call-examples-file (File. "src/skeptic/static_call_examples.clj"))

(defn- build-static-call-dict
  []
  (let [{:keys [entries]} (admit/plumatic-args 'skeptic.static-call-examples static-call-examples-file)]
    (:dict (typed-decls/typed-ns-results {} 'skeptic.static-call-examples :clj nil entries))))

(def static-call-dict
  (delay
    (if oracle/*worker-conn*
      (build-static-call-dict)
      (shared-worker/with-shared-worker build-static-call-dict))))

(def cc-dict (catalog/typed-test-example-entries))
(def cc-ns 'skeptic.test-examples.call-cases)

(defn- def-body
  "Returns the value-node body of fixture def `name` in worker-analyzed `asts`."
  [asts name]
  (let [def-node (atst/ast-by-name asts name)
        method (first (aapi/def-fn-methods def-node))]
    (aapi/method-body method)))

(defn- cc-body
  [name]
  (def-body (aat/analyze-ns-file cc-dict cc-ns (atst/fixture-file-for-ns cc-ns) {}) name))

(defn- scc-asts []
  (aat/analyze-ns-file @static-call-dict 'skeptic.static-call-examples static-call-examples-file {}))

(defn assert-typed-call-metadata-only
  [node]
  (is (some? (aapi/node-type node)))
  (is (vector? (aapi/call-actual-argtypes node)))
  (is (aapi/typed-call-metadata-only? node)))

(deftest calls-predicate-and-qualify-unit-test
  (testing "qualify-symbol"
    (is (nil? (ac/qualify-symbol nil nil)))
    (is (= 'already/qualified (ac/qualify-symbol 'foo 'already/qualified)))
    (is (= 'my.ns/x (ac/qualify-symbol 'my.ns 'x)))
    (is (= 'x (ac/qualify-symbol nil 'x))))
  (testing "behavioral call-kind outcomes through public API"
    (let [ctx tp
          marker (T s/Str)
          m-arg  (aat/test-typed-node :local 'm (T s/Any))
          k-arg  (aat/test-typed-node :local 'k (T s/Any))]
      ;; The invoke-output-type kind subsystem recognizes invoke-get,
      ;; invoke-merge, invoke-contains and dispatches into shared-call,
      ;; producing a result distinct from the marker default.
      (is (not= marker
                (ck-invoke-output/invoke-output-type ctx (aat/test-fn-node 'merge) [m-arg m-arg] marker)))
      (is (not= marker
                (ck-invoke-output/invoke-output-type ctx (aat/test-fn-node 'contains?) [m-arg k-arg] marker)))
      (is (not= marker
                (ck-invoke-output/invoke-output-type ctx (aat/test-fn-node 'get) [m-arg k-arg] marker)))
      ;; static-call-output-type returns nil for unknown shapes, non-nil for known.
      (is (some? (ck-static/static-call-output-type ctx (aat/test-static-call-node clojure.lang.RT 'get) [m-arg k-arg] marker)))
      (is (some? (ck-static/static-call-output-type ctx (aat/test-static-call-node clojure.lang.RT 'merge) [m-arg m-arg] marker)))
      (is (some? (ck-static/static-call-output-type ctx (aat/test-static-call-node clojure.lang.RT 'contains?) [m-arg k-arg] marker)))
      ;; Sanity: unknown clojure.lang.RT method returns nil.
      (is (nil? (ck-static/static-call-output-type ctx (aat/test-static-call-node clojure.lang.RT 'unknown-method) [m-arg k-arg] marker))))))

(deftest invoke-and-static-application-roots-test
  (testing "do, static-call, and invoke roots"
    (let [do-form (cc-body 'cc-do-roots)
          plus-form (cc-body 'cc-plus-local)
          local-invoke (cc-body 'cc-zero-arity-invoke)
          nested-invoke (cc-body 'cc-nested-invoke)]
      (is (= :do (aapi/node-op do-form)))
      (is (= :static-call (aapi/node-op plus-form)))
      (is (= :invoke (aapi/node-op local-invoke)))
      (is (= :invoke (aapi/node-op nested-invoke))))))

(deftest typed-application-call-test
  (testing "application types"
    (let [dynamic-call (cc-body 'cc-dynamic-plus)
          unknown-invoke (cc-body 'cc-unknown-invoke)
          known-call (cc-body 'cc-known-call)]
      (let [args (aapi/call-actual-argtypes dynamic-call)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Int) (nth args 1)))
      (is-type= atst/numeric-dyn (aapi/node-type dynamic-call))
      (assert-typed-call-metadata-only dynamic-call)
      (is-type= (T (s/make-fn-schema s/Any [[s/Any s/Any]]))
                (aapi/node-fn-type unknown-invoke))
      (assert-typed-call-metadata-only unknown-invoke)
      (let [args (aapi/call-actual-argtypes known-call)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Int) (nth args 1)))
      (let [args (aapi/call-expected-argtypes known-call)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Int) (nth args 1)))
      (is-type= (T s/Int) (aapi/node-type known-call))
      (assert-typed-call-metadata-only known-call))))

(deftest analyse-application-test
  (testing "original partially unknown application setup"
    (let [root (cc-body 'cc-plus-local)]
      (is (= :static-call (aapi/node-op root)))
      (let [args (aapi/call-actual-argtypes root)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Any) (nth args 1)))))
  (testing "original zero-arity application setup"
    (let [root (cc-body 'cc-zero-arity-invoke)]
      (is (= :invoke (aapi/node-op root)))
      (is (= 0 (count (aapi/call-actual-argtypes root))))
      (assert-typed-call-metadata-only root)))
  (testing "original nested application setup"
    (let [root (cc-body 'cc-nested-invoke)]
      (is (= :invoke (aapi/node-op root)))
      (is (= 2 (count (aapi/call-actual-argtypes root))))
      (is (= '(f 1) (aapi/node-form (aapi/call-fn-node root))))
      (assert-typed-call-metadata-only root))))

(deftest attach-type-info-application-test
  (testing "original generic application typed setup"
    (let [root (cc-body 'cc-dynamic-plus)]
      (let [args (aapi/call-actual-argtypes root)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Int) (nth args 1)))
      (is-type= atst/numeric-dyn (aapi/node-type root))
      (assert-typed-call-metadata-only root)))
  (testing "original known application typed setup"
    (let [root (cc-body 'cc-known-call)]
      (let [args (aapi/call-actual-argtypes root)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Int) (nth args 1)))
      (let [args (aapi/call-expected-argtypes root)]
        (is (= 2 (count args)))
        (is-type= (T s/Int) (nth args 0))
        (is-type= (T s/Int) (nth args 1)))
      (is-type= (T s/Int) (aapi/node-type root))
      (assert-typed-call-metadata-only root))))

(deftest canonicalized-callable-entry-test
  (let [asts (aat/analyze-ns-file cc-dict cc-ns (atst/fixture-file-for-ns cc-ns) {})
        symbol-call (def-body asts 'cc-symbol-call)
        keyword-call (def-body asts 'cc-keyword-call)
        int-call (def-body asts 'cc-int-call)
        quoted-symbol (def-body asts 'cc-quoted-symbol)]
    (is-type= (T s/Symbol) (aapi/node-type symbol-call))
    (let [args (aapi/call-expected-argtypes symbol-call)]
      (is (= 1 (count args)))
      (is-type= (T s/Str) (first args)))
    (is-type= (T s/Keyword) (aapi/node-type keyword-call))
    (is-type= (T s/Int) (aapi/node-type int-call))
    (is-type= (T s/Symbol) (aapi/node-type quoted-symbol))
    (assert-typed-call-metadata-only symbol-call)
    (assert-typed-call-metadata-only keyword-call)
    (assert-typed-call-metadata-only int-call)))

(deftest static-call-analysis-test
  (let [asts (scc-asts)]
    (testing "get returns declared field types from typed maps"
      (let [required-get (def-body asts 'required-name)
            optional-get (def-body asts 'optional-nickname)
            defaulted-get (def-body asts 'bad-count-default)]
        (is-type= (T s/Str) (aapi/node-type required-get))
        (is-type= (T (s/maybe s/Str)) (aapi/node-type optional-get))
        (is-type= (T (sb/join s/Int s/Str))
                  (aapi/node-type defaulted-get))))

    (testing "merge returns merged typed maps"
      (let [merged (def-body asts 'merge-fields)]
        (is-type= (T {:a s/Int :b s/Int})
                  (aapi/node-type merged))))

    (testing "rebuilt maps stay in semantic map format"
      (let [root (def-body asts 'rebuilt-user)]
        (is-type= (T {:name s/Str
                      :nickname (s/maybe s/Str)})
                  (aapi/node-type root))))))

(deftest resolved-static-get-feeds-parent-call-test
  (testing "resolved static get feeds final reduced field types into parent calls"
    (let [failure-ast (atst/ast-by-name (scc-asts) 'nested-multi-step-failure)
          call-node (atst/node-by-form failure-ast '(nested-multi-step-takes-str (get (nested-multi-step-g) :value)))]
      (let [args (aapi/call-actual-argtypes call-node)]
        (is (= 1 (count args)))
        (is-type= (T s/Int) (first args)))
      (assert-typed-call-metadata-only call-node))))

(deftest attach-type-info-local-fn-invocation-test
  (let [root (cc-body 'cc-local-fn-invocation)
        inner (aapi/find-node root #(= '(f x) (aapi/node-form %)))]
    (testing "local fn invocation through int-add"
      (is-type= (T s/Int) (aapi/node-type root))
      (is-type= (T (s/eq nil)) (aapi/node-type inner))
      (assert-typed-call-metadata-only inner))
    (testing "local fn invocation keeps callable metadata with outer local"
      (is-type= (T s/Int) (aapi/node-type root))
      (is-type= (T (s/eq nil)) (aapi/node-type inner))
      (assert-typed-call-metadata-only inner))))
