(ns skeptic.checking.ast-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.checking.ast :as ca]
            [skeptic.test-helpers :refer [T]]))

(deftest distinctv-test
  (is (= [1 2 3] (ca/distinctv [1 2 1 3 2])))
  (is (= [] (ca/distinctv []))))

(deftest child-nodes-and-preorder-test
  (let [leaf {:children [] :op :const :id :leaf}
        inner {:children [:a] :a leaf :op :vec :id :inner}
        root {:children [:b] :b inner :op :body :id :root}]
    (is (= [inner] (sac/child-nodes root)))
    (is (= [:root :inner :leaf] (map :id (sac/ast-nodes root))))))

(deftest node-ref-and-callee-ref-test
  (let [tx (T s/Any)
        tf (T s/Any)
        tg (T s/Any)
        e (T s/Any)
        a (T s/Any)]
    (is (= {:form 'x :type tx} (ca/node-ref (assoc (aat/test-typed-node :local 'x tx) :extra 1))))
    (is (nil? (ca/node-ref nil)))
    (is (= {:form 'f :type tf}
           (ca/callee-ref (aat/test-invoke-node (aat/test-typed-node :var 'f tf) [] [e] [a]))))
    (is (= {:form 'g :type tg}
           (ca/callee-ref (aat/test-with-meta-node
                           (aat/test-invoke-node (aat/test-typed-node :var 'g tg) [] [e] [a])))))
    (is (nil? (ca/callee-ref (aat/test-const-node 1))))))

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

(deftest local-resolution-path-test
  (let [tf (T s/Any) ti (T s/Any)
        fn-part (aat/test-typed-node :var '+ tf)
        init (aat/test-invoke-form-node fn-part [] '(+ 1 2) ti)
        local-node (aat/test-local-node 'g init)
        path (ca/local-resolution-path local-node)]
    (is (= 2 (count path)))
    (is (= (ca/node-ref fn-part) (second path)))))

(deftest local-vars-context-test
  (let [ast (aa/analyze-form '(let [x 1] x))
        ctx (ca/local-vars-context ast)]
    (is (contains? ctx 'x))
    (is (= 'x (:form (get ctx 'x))))
    (is (vector? (:resolution-path (get ctx 'x))))))

(deftest call-refs-test
  (let [tf (T s/Any) e (T s/Any) a (T s/Any)
        fn-local (aat/test-local-node 'f (aat/test-typed-node :const nil tf))
        invoke (aat/test-invoke-node fn-local [] [e] [a])]
    (is (seq (ca/call-refs invoke)))))

(deftest call-node?-test
  (let [e (T s/Any) a (T s/Any)]
    (is (ca/call-node? (aat/test-invoke-node (aat/test-fn-node 'f) [a] [e] [a])))
    (is (not (ca/call-node? (aat/test-invoke-node (aat/test-fn-node 'f) [] [] []))))
    (is (not (ca/call-node? (aat/test-const-node 1))))))

(deftest dict-entry-test
  (is (= :direct (ca/dict-entry {'x :direct} 'my.ns 'x)))
  (is (= :qualified (ca/dict-entry {'my.ns/x :qualified} 'my.ns 'x))))

(deftest unwrap-with-meta-test
  (let [const (aat/test-const-node 1)]
    (is (= const
           (ca/unwrap-with-meta (aat/test-with-meta-node
                                 (aat/test-with-meta-node const)))))))

(deftest invoke-ops-test
  (is (contains? ca/invoke-ops :invoke))
  (is (contains? ca/invoke-ops :static-call)))
