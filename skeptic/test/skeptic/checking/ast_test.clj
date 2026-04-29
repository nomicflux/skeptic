(ns skeptic.checking.ast-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.ast-children :as sac]
            [skeptic.checking.ast :as ca]))

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
  (is (= {:form 'x :type :tx} (ca/node-ref (assoc (aat/test-typed-node :local 'x :tx) :extra 1))))
  (is (nil? (ca/node-ref nil)))
  (is (= {:form 'f :type :tf}
         (ca/callee-ref (aat/test-invoke-node (aat/test-typed-node :var 'f :tf) [] [:e] [:a]))))
  (is (= {:form 'g :type :tg}
         (ca/callee-ref (aat/test-with-meta-node
                         (aat/test-invoke-node (aat/test-typed-node :var 'g :tg) [] [:e] [:a])))))
  (is (nil? (ca/callee-ref (aat/test-const-node 1)))))

(deftest match-up-arglists-test
  (let [a (aat/test-typed-node :const 'a :ta)
        b (aat/test-typed-node :const 'b :tb)
        pairs (vec (ca/match-up-arglists [a b nil] [:e0 :e1] [:x0 :x1 :x2]))]
    (is (= 3 (count pairs)))
    (is (= [a :e0 :x0] (first pairs)))
    (is (= [nil :e1 :x2] (last pairs)))))

(deftest local-resolution-path-test
  (let [fn-part (aat/test-typed-node :var '+ :tf)
        init (aat/test-invoke-form-node fn-part [] '(+ 1 2) :ti)
        local-node (aat/test-local-node 'g init)
        path (ca/local-resolution-path local-node)]
    (is (= 2 (count path)))
    (is (= (ca/node-ref fn-part) (second path)))))

(deftest local-vars-context-test
  (let [ast (ana.jvm/analyze '(let [x 1] x))
        ctx (ca/local-vars-context ast)]
    (is (contains? ctx 'x))
    (is (= 'x (:form (get ctx 'x))))
    (is (vector? (:resolution-path (get ctx 'x))))))

(deftest call-refs-test
  (let [fn-local (aat/test-local-node 'f (aat/test-typed-node :const nil :tf))
        invoke (aat/test-invoke-node fn-local [] [:e] [:a])]
    (is (seq (ca/call-refs invoke)))))

(deftest call-node?-test
  (is (ca/call-node? (aat/test-invoke-node (aat/test-fn-node 'f) [:a] [:e] [:a])))
  (is (not (ca/call-node? (aat/test-invoke-node (aat/test-fn-node 'f) [] [] []))))
  (is (not (ca/call-node? {:op :const}))))

(deftest dict-entry-test
  (is (= :direct (ca/dict-entry {'x :direct} 'my.ns 'x)))
  (is (= :qualified (ca/dict-entry {'my.ns/x :qualified} 'my.ns 'x))))

(deftest unwrap-with-meta-test
  (is (= {:op :const :val 1}
         (ca/unwrap-with-meta (aat/test-with-meta-node
                               (aat/test-with-meta-node {:op :const :val 1}))))))

(deftest invoke-ops-test
  (is (contains? ca/invoke-ops :invoke))
  (is (contains? ca/invoke-ops :static-call)))
