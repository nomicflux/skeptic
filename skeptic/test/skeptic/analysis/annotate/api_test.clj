(ns skeptic.analysis.annotate.api-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis-test :as atst]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-helpers :refer [T]]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(deftest node-ref-and-callee-ref-test
  (let [tx (T s/Any)
        tf (T s/Any)
        tg (T s/Any)
        e (T s/Any)
        a (T s/Any)]
    (is (= {:form 'x :type tx} (aapi/node-ref (assoc (aat/test-typed-node :local 'x tx) :extra 1))))
    (is (nil? (aapi/node-ref nil)))
    (is (= {:form 'f :type tf}
           (aapi/callee-ref (aat/test-invoke-node (aat/test-typed-node :var 'f tf) [] [e] [a]))))
    (is (= {:form 'g :type tg}
           (aapi/callee-ref (aat/test-with-meta-node
                             (aat/test-invoke-node (aat/test-typed-node :var 'g tg) [] [e] [a])))))
    (is (nil? (aapi/callee-ref (aat/test-const-node 1))))))

(deftest local-resolution-path-test
  (let [tf (T s/Any) ti (T s/Any)
        fn-part (aat/test-typed-node :var '+ tf)
        init (aat/test-invoke-form-node fn-part [] '(+ 1 2) ti)
        local-node (aat/test-local-node 'g init)
        path (aapi/local-resolution-path local-node)]
    (is (= 2 (count path)))
    (is (= (aapi/node-ref fn-part) (second path)))))

(deftest local-vars-context-test
  (let [sc-ns 'skeptic.test-examples.structural-cases
        asts (aat/analyze-ns-file (catalog/typed-test-example-entries) sc-ns
                                  (atst/fixture-file-for-ns sc-ns) {})
        def-node (atst/ast-by-name asts 'sc-let-x)
        ast (aapi/method-body (first (aapi/def-fn-methods def-node)))
        ctx (aapi/local-vars-context ast)]
    (is (contains? ctx 'x))
    (is (= 'x (:form (get ctx 'x))))
    (is (vector? (:resolution-path (get ctx 'x))))))

(deftest call-refs-test
  (let [tf (T s/Any) e (T s/Any) a (T s/Any)
        fn-local (aat/test-local-node 'f (aat/test-typed-node :const nil tf))
        invoke (aat/test-invoke-node fn-local [] [e] [a])]
    (is (seq (aapi/call-refs invoke)))))

(deftest call-node?-test
  (let [e (T s/Any) a (T s/Any)]
    (is (aapi/call-node? (aat/test-invoke-node (aat/test-fn-node 'f) [a] [e] [a])))
    (is (not (aapi/call-node? (aat/test-invoke-node (aat/test-fn-node 'f) [] [] []))))
    (is (not (aapi/call-node? (aat/test-const-node 1))))))

(deftest unwrap-with-meta-test
  (let [const (aat/test-const-node 1)]
    (is (= const
           (aapi/unwrap-with-meta (aat/test-with-meta-node
                                   (aat/test-with-meta-node const)))))))

(deftest invoke-ops-test
  (is (contains? aapi/invoke-ops :invoke))
  (is (contains? aapi/invoke-ops :static-call)))
