(ns skeptic.analysis.annotate.structural-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.test-helpers :refer [is-type= T]]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.types :as at]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def ^:private sc-dict (catalog/typed-test-example-entries))
(def ^:private sc-ns 'skeptic.test-examples.structural-cases)

(defn- sc-body
  [name]
  (let [asts (aat/analyze-ns-file sc-dict sc-ns (atst/fixture-file-for-ns sc-ns) {})
        def-node (atst/ast-by-name asts name)]
    (aapi/method-body (first (aapi/def-fn-methods def-node)))))

(deftest structural-throw-try-and-loop-test
  (testing "throw stays bottom typed"
    (let [ast (sc-body 'sc-throw)]
      (is (= :throw (aapi/node-op ast)))
      (is (at/bottom-type? (aapi/node-type ast)))))
  (testing "try joins body and catch outputs"
    (let [ast (sc-body 'sc-try-catch)]
      (is (= :try (aapi/node-op ast)))
      (is (some? (aapi/node-type ast)))))
  (testing "loop and recur preserve structural nodes"
    (let [ast (sc-body 'sc-loop-recur)
          recur-node (aapi/find-node ast #(= :recur (aapi/node-op %)))]
      (is (= :loop (aapi/node-op ast)))
      (is (at/bottom-type? (aapi/node-type recur-node)))
      (is (= 1 (count (aapi/recur-args recur-node)))))))

(deftest structural-literal-collections-test
  (let [vec-ast (aapi/unwrap-with-meta (sc-body 'sc-vec-literal))
        map-ast (aapi/unwrap-with-meta (sc-body 'sc-map-literal))
        set-ast (aapi/unwrap-with-meta (sc-body 'sc-set-literal))]
    (is (= :vector (aapi/node-op vec-ast)))
    (is (= :map (aapi/node-op map-ast)))
    (is (= :set (aapi/node-op set-ast)))
    (is-type= (T [(s/one s/Int 'a) (s/one s/Int 'b)]) (aapi/node-type vec-ast))))
