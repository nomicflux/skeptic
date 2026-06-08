(ns skeptic.checking.ast-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.checking.ast :as ca]
            [skeptic.test-helpers :refer [T]]))

(deftest distinctv-test
  (is (= [1 2 3] (ca/distinctv [1 2 1 3 2])))
  (is (= [] (ca/distinctv []))))

(deftest match-up-arglists-test
  (let [ta (T s/Any) tb (T s/Any)
        a (aat/test-typed-node :const 'a ta)
        b (aat/test-typed-node :const 'b tb)
        e0 (T s/Any) e1 (T s/Any)
        x0 (T s/Any) x1 (T s/Any) x2 (T s/Any)
        pairs (vec (ca/match-up-arglists [a b nil] [e0 e1] [x0 x1 x2]))]
    (is (= 3 (count pairs)))
    (is (= [a e0 x0] (first pairs)))
    (is (= [nil e1 x2] (last pairs)))))

(deftest dict-entry-test
  (is (= :direct (ca/dict-entry {'x :direct} 'my.ns 'x)))
  (is (= :qualified (ca/dict-entry {'my.ns/x :qualified} 'my.ns 'x))))
