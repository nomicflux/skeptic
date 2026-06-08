(ns skeptic.analysis.ast-children-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.ast-children :as sac]))

(deftest child-nodes-and-preorder-test
  (let [leaf {:children [] :op :const :id :leaf}
        inner {:children [:a] :a leaf :op :vec :id :inner}
        root {:children [:b] :b inner :op :body :id :root}]
    (is (= [inner] (sac/child-nodes root)))
    (is (= [:root :inner :leaf] (map :id (sac/ast-nodes root))))))
