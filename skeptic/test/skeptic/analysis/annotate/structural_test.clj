(ns skeptic.analysis.annotate.structural-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis-test :as atst]
            [skeptic.analysis.types :as at]))

(deftest structural-throw-try-and-loop-test
  (testing "throw stays bottom typed"
    (let [ast (atst/analyze-form '(throw (Exception. "boom")))]
      (is (= :throw (aapi/node-op ast)))
      (is (at/bottom-type? (aapi/node-type ast)))))
  (testing "try joins body and catch outputs"
    (let [ast (atst/analyze-form '(try 1 (catch Exception e e)))]
      (is (= :try (aapi/node-op ast)))
      (is (some? (aapi/node-type ast)))))
  (testing "loop and recur preserve structural nodes"
    (let [ast (atst/analyze-form '(loop [x 0] (recur (inc x))))
          recur-node (aapi/find-node ast #(= :recur (aapi/node-op %)))]
      (is (= :loop (aapi/node-op ast)))
      (is (at/bottom-type? (aapi/node-type recur-node)))
      (is (= 1 (count (aapi/recur-args recur-node)))))))

(deftest structural-literal-collections-test
  (let [vec-ast (atst/analyze-form '[1 2])
        map-ast (atst/analyze-form '{:a 1})
        set-ast (atst/analyze-form '#{1 2})]
    (is (= :const (aapi/node-op vec-ast)))
    (is (= :const (aapi/node-op map-ast)))
    (is (= :const (aapi/node-op set-ast)))
    (is (at/type=? (atst/T [s/Int s/Int]) (aapi/node-type vec-ast)))))
