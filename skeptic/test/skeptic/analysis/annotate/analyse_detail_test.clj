(ns skeptic.analysis.annotate.analyse-detail-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.value :as av]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]))

(deftest analyse-let-test
  (testing "original empty let setup"
    (let [root (atst/project-ast (atst/analyze-form '(let [] (+ 1 2))))]
      (is (= :static-call (:op root)))
      (is (= atst/num-ground (:type root)))))
  (testing "original simple let setup"
    (let [root (atst/project-ast (atst/analyze-form '(let [x 1] (+ 1 x))))]
      (is (= :let (:op root)))
      (is (= 1 (count (atst/child-projection root :bindings))))))
  (testing "original nested let setup"
    (let [root (atst/project-ast (atst/analyze-form '(let [x (+ 1 2)
                                                 y (+ 3 x)]
                                             (+ 7 x)
                                             (+ x y))))]
      (is (= :let (:op root)))
      (is (= 2 (count (atst/child-projection root :bindings))))
      (is (= :do (:op (atst/child-projection root :body))))
      (is (= :static-call (:op (atst/child-projection (atst/child-projection root :body) :ret)))))))

(deftest analyse-if-test
  (testing "original literal if setup"
    (let [root (atst/project-ast (atst/analyze-form '(if (even? 2) true "hello")))]
      (is (= :if (:op root)))
      (is (= [:test :then :else] (mapv first (:children root))))))
  (testing "original symbol if setup"
    (let [root (atst/project-ast (atst/analyze-form '(if (pos? x) 1 -1)))]
      (is (= :if (:op root)))
      (is (= '(if (pos? x) 1 -1) (:form root))))))

(deftest analyse-fn-test
  (testing "original anonymous identity fn setup"
    (let [root (atst/project-ast (atst/analyze-form '(fn [x] x)))]
      (is (= :fn (:op root)))
      (is (= 1 (count (atst/child-projection root :methods))))
      (is (= at/Dyn (:output-type root)))))
  (testing "original named identity fn setup"
    (let [root (atst/project-ast (atst/analyze-form atst/sample-dict
                                          '(fn [x] x)
                                          {:name 'f}))]
      (is (= :fn (:op root)))
      (is (= [(atst/T s/Int)] (-> root :arglists (get 1) :types (->> (mapv :type)))))
      (is (= (atst/T s/Int) (:output-type root)))))
  (testing "original named binary fn setup"
    (let [root (atst/project-ast (atst/analyze-form atst/sample-dict
                                          '(fn [y z] y)
                                          {:name 'f}))]
      (is (= :fn (:op root)))
      (is (= [(atst/T s/Str) (atst/T s/Int)] (-> root :arglists (get 2) :types (->> (mapv :type)))))
      (is (= (atst/T s/Str) (:output-type root)))))
  (testing "original multi-expression fn setup"
    (let [root (atst/project-ast (atst/analyze-form '(fn [x y]
                                             (str y x)
                                             (+ x y))))]
      (is (= :fn (:op root)))
      (is (= atst/num-ground (:output-type root)))
      (is (= 1 (count (atst/child-projection root :methods))))))
  (testing "original multi-arity fn setup"
    (let [root (atst/project-ast (atst/analyze-form '(fn* ([x] (+ x 1))
                                              ([x y] (+ x y)))))]
      (is (= :fn (:op root)))
      (is (= 2 (count (atst/child-projection root :methods))))
      (is (= 2 (count (:arglists root)))))))

(deftest analyse-def-test
  (testing "original def setup"
    (let [root (atst/project-ast (atst/analyze-form '(def n 5)))]
      (is (= :def (:op root)))
      (is (= 'n (:name root)))
      (is (= (at/->VarT (atst/T s/Int)) (:type root)))))
  (testing "original defn setup"
    (let [root (atst/project-ast (atst/analyze-form '(defn f [x]
                                            (println "something")
                                            (+ 1 x))))]
      (is (= :def (:op root)))
      (is (= 'f (:name root)))
      (is (= :with-meta (:op (atst/child-projection root :init))))
      (is (some #{'(defn f [x] (println "something") (+ 1 x))}
                (:raw-forms root))))))

(deftest analyse-do-test
  (let [root (atst/project-ast (atst/analyze-form '(do (str "hello") (+ 1 2))))]
    (is (= :do (:op root)))
    (is (= :static-call (:op (atst/child-projection root :ret))))))

(deftest analyse-coll-test
  (testing "original vector literal setup"
    (let [root (atst/project-ast (atst/analyze-form '[1 2 :a "hello"]))]
      (is (= :const (:op root)))
      (is (at/vector-type? (:type root)))))
  (testing "original set literal setup"
    (let [root (atst/project-ast (atst/analyze-form '#{1 2 :a "hello"}))]
      (is (= :const (:op root)))
      (is (at/set-type? (:type root)))))
  (testing "original list literal setup"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"is not a function"
         (atst/analyze-form '(1 2 :a "hello")))))
  (testing "original map literal setup"
    (let [root (atst/project-ast (atst/analyze-form '{:a 1 :b 2 :c 3}))]
      (is (= :const (:op root)))
      (is (at/map-type? (:type root)))))
  (testing "original nested vector setup"
    (let [root (atst/project-ast (atst/analyze-form '[1 2 [3 4 [5]]]))]
      (is (= :const (:op root)))
      (is (at/vector-type? (:type root)))))
  (testing "original nested map setup"
    (let [root (atst/project-ast (atst/analyze-form '{:a 1 :b [:z "hello" #{1 2}]
                                           :c {:d 7 :e {:f 9}}}))]
      (is (= :const (:op root)))
      (is (at/map-type? (:type root))))))

(deftest map-literal-key-boundary-test
  (testing "literal raw keys are converted through the raw-value boundary"
    (let [uuid-key #uuid "550e8400-e29b-41d4-a716-446655440000"
          regex-key #"^[\u0020-\u007e]*$"
          root (atst/analyze-form '(let [v 1]
                                     {#uuid "550e8400-e29b-41d4-a716-446655440000" v
                                      #"^[\u0020-\u007e]*$" v}))
          map-node (:body root)
          entries (-> map-node :type :entries)
          key-types (keys entries)
          uuid-entry (some #(when (and (at/value-type? %)
                                       (= uuid-key (:value %)))
                              %)
                           key-types)
          regex-entry (some #(when (and (at/value-type? %)
                                        (instance? java.util.regex.Pattern (:value %))
                                        (= (.pattern regex-key)
                                           (.pattern ^java.util.regex.Pattern (:value %))))
                               %)
                            key-types)]
      (is (= :map (:op map-node)))
      (is (= (av/exact-runtime-value-type uuid-key) uuid-entry))
      (is (= java.util.UUID (-> uuid-entry :inner :ground :class)))
      (is (= (atst/T s/Int) (get entries uuid-entry)))
      (is (some? regex-entry))
      (is (= java.util.regex.Pattern (-> regex-entry :inner :ground :class)))
      (is (= (.pattern regex-key)
             (.pattern ^java.util.regex.Pattern (:value regex-entry))))
      (is (= (atst/T s/Int) (get entries regex-entry)))))
  (testing "non-literal keys use inferred semantic key types"
    (let [root (atst/analyze-form '(let [v 1]
                                     {k v})
                                  (atst/local-types {'k {:type (atst/T s/Keyword)}}))
          map-node (:body root)]
      (is (= :map (:op map-node)))
      (is (= {(atst/T s/Keyword) (atst/T s/Int)}
             (-> map-node :type :entries))))))
