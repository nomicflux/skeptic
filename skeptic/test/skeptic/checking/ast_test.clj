(ns skeptic.checking.ast-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.tools.analyzer.jvm :as ana.jvm]
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
  (is (= {:form 'x :type :tx} (ca/node-ref {:form 'x :type :tx :extra 1})))
  (is (nil? (ca/node-ref nil)))
  (is (= {:form 'f :type :tf}
         (ca/callee-ref {:op :invoke :fn {:form 'f :type :tf}})))
  (is (= {:form 'g :type :tg}
         (ca/callee-ref {:op :with-meta
                         :expr {:op :invoke :fn {:form 'g :type :tg}}})))
  (is (nil? (ca/callee-ref {:op :const}))))

(deftest match-up-arglists-test
  (let [pairs (ca/match-up-arglists [:a :b nil] [:e0 :e1] [:x0 :x1 :x2])]
    (is (= 3 (count pairs)))
    (is (= [:a :e0 :x0] (first pairs)))
    (is (= [nil :e1 :x2] (last pairs)))))

(deftest local-resolution-path-test
  (let [fn-part {:form '+ :type :tf}
        init {:op :invoke :fn fn-part :form '(+ 1 2) :type :ti}
        local-node {:form 'g :op :local :binding-init init}
        path (ca/local-resolution-path local-node)]
    (is (= 2 (count path)))
    (is (= fn-part (second path)))))

(deftest local-vars-context-test
  (let [ast (ana.jvm/analyze '(let [x 1] x))
        ctx (ca/local-vars-context ast)]
    (is (contains? ctx 'x))
    (is (= 'x (:form (get ctx 'x))))
    (is (vector? (:resolution-path (get ctx 'x))))))

(deftest call-refs-test
  (let [fn-local {:op :local :form 'f :type :tf}
        invoke {:op :invoke :fn fn-local :args []}]
    (is (seq (ca/call-refs invoke)))))

(deftest call-node?-test
  (is (ca/call-node? {:op :invoke
                      :args [:a]
                      :expected-argtypes [:e]
                      :actual-argtypes [:a]}))
  (is (not (ca/call-node? {:op :invoke :args [] :expected-argtypes [] :actual-argtypes []})))
  (is (not (ca/call-node? {:op :const}))))

(deftest dict-entry-test
  (is (= :direct (ca/dict-entry {'x :direct} 'my.ns 'x)))
  (is (= :qualified (ca/dict-entry {'my.ns/x :qualified} 'my.ns 'x))))

(deftest unwrap-with-meta-test
  (is (= {:op :const :val 1}
         (ca/unwrap-with-meta {:op :with-meta
                               :expr {:op :with-meta
                                      :expr {:op :const :val 1}}}))))

(deftest invoke-ops-test
  (is (contains? ca/invoke-ops :invoke))
  (is (contains? ca/invoke-ops :static-call)))