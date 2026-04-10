(ns skeptic.analysis.annotate.typed-flow-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]))

(deftest typed-binding-and-flow-test
  (testing "let-driven flow"
    (let [empty-let (atst/project-ast (atst/analyze-form '(let [] (skeptic.test-examples/int-add 1 2))))
          bound-let (atst/project-ast (atst/analyze-form '(let [x (skeptic.test-examples/int-add 1 2)]
                                                  (skeptic.test-examples/int-add x 2))))]
      (is (= (atst/T s/Int) (:type empty-let)))
      (is (= (atst/T s/Int) (:type bound-let))))))

(deftest typed-functions-and-defs-test
  (testing "anonymous and named typed fns"
    (let [anon-fn (atst/project-ast (atst/analyze-form '(fn [x] x)))
          named-fn (atst/project-ast (atst/analyze-form atst/sample-dict
                                              '(fn [x] x)
                                              {:name 'f}))
          named-binary-fn (atst/project-ast (atst/analyze-form atst/sample-dict
                                                     '(fn [y z] y)
                                                     {:name 'f}))
          body-fn (atst/project-ast (atst/analyze-form '(fn [x y]
                                                (str y x)
                                                (+ x y))))
          multi-fn (atst/project-ast (atst/analyze-form '(fn* ([x] (+ x 1))
                                                    ([x y] (+ x y)))))
          anon-method (first (atst/child-projection anon-fn :methods))]
      (is (= (atst/T s/Any) (:output-type anon-fn)))
      (is (= [{:type (atst/T s/Any) :optional? false :name 'x}]
             (:param-specs anon-method)))
      (is (= [(atst/T s/Int)] (-> named-fn :arglists (get 1) :types (->> (mapv :type)))))
      (is (= (atst/T s/Int) (:output-type named-fn)))
      (is (= [(atst/T s/Str) (atst/T s/Int)] (-> named-binary-fn :arglists (get 2) :types (->> (mapv :type)))))
      (is (= (atst/T s/Str) (:output-type named-binary-fn)))
      (is (= 2 (count (:arglists multi-fn))))
      (is (= atst/num-ground (:output-type body-fn)))))

  (testing "fn bodies, defs, and do blocks"
    (let [fn-call (atst/project-ast (atst/analyze-form '(fn [x]
                                                (skeptic.test-examples/int-add 1 2))))
          def-form (atst/project-ast (atst/analyze-form '(def n 5)))
          defn-form (atst/project-ast (atst/analyze-form '(defn f [x y]
                                                  (println "something")
                                                  (skeptic.test-examples/int-add x y))))
          typed-defn (atst/project-ast (atst/analyze-form atst/sample-dict
                                                '(defn f [y z]
                                                   (println y)
                                                   z)))
          do-form (atst/project-ast (atst/analyze-form '(do
                                               (println "something")
                                               (skeptic.test-examples/int-add x y))
                                             (atst/locals 'x 'y)))]
      (is (= (atst/T s/Int) (:output-type fn-call)))
      (is (= (at/->VarT (atst/T s/Int)) (:type def-form)))
      (is (= 'f (:name defn-form)))
      (is (at/var-type? (:type defn-form)))
      (is (= (atst/T s/Int)
             (:output-type (atst/child-projection (atst/child-projection typed-defn :init) :expr))))
      (is (= (atst/T s/Int) (:type do-form))))))

(deftest typed-try-and-throw-test
  (testing "try with throwing catch preserves body result"
    (let [root (atst/project-ast (atst/analyze-form '(try
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (throw e)))))]
      (is (= (atst/T s/Int) (:type root)))
      (is (= at/BottomType
             (:type (atst/find-projected-node root #(= '(throw e) (:form %))))))))

  (testing "try with body, catch, and finally stays analyzable"
    (let [root (atst/project-ast (atst/analyze-form '(try
                                             (clojure.core/str "hello")
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (println "oops")
                                               (throw e))
                                             (finally
                                               (skeptic.test-examples/int-add 3 4)
                                               (clojure.core/str "world")))))]
      (is (= (atst/T s/Int) (:type root)))
      (is (= :do (:op (atst/child-projection root :finally))))
      (is (atst/find-projected-node root #(= '(println "oops") (:form %)))))))

(deftest misc-and-macro-edge-cases-test
  (testing "nested bad call remains analyzable"
    (let [root (atst/project-ast (atst/analyze-form '(defn sample-bad-fn
                                             [x]
                                             (skeptic.test-examples/int-add
                                              1
                                              (skeptic.test-examples/int-add nil x)))))]
      (is (at/var-type? (:type root)))
      (is (atst/find-projected-node root #(= '(skeptic.test-examples/int-add nil x) (:form %))))
      (is (atst/find-projected-node root #(= '(skeptic.test-examples/int-add 1 (skeptic.test-examples/int-add nil x))
                                        (:form %))))))

  (testing "fn once metadata is preserved in analyzer form"
    (let [root (atst/project-ast (atst/analyze-form '(defn sample-fn-once
                                             [x]
                                             ((^{:once true} fn* [y] (int-add y nil))
                                              x))
                                           (atst/locals 'int-add)))]
      (is (at/var-type? (:type root)))
      (is (atst/find-projected-node root #(= :with-meta (:op %))))))

  (testing "local callable from let-bound value stays conservative"
    (let [root (atst/project-ast (atst/analyze-form '(defn sample-path-fn
                                             [x]
                                             (let [f (+ 1 x)]
                                               (f x)))))]
      (is (at/var-type? (:type root)))
      (is (atst/find-projected-node root #(= '(f x) (:form %))))))

  (testing "doto expansion keeps literal Plumatic map schema"
    (let [root (atst/project-ast (atst/analyze-form '(doto (make-component {:a 1 :b 2})
                                             (start {:opt1 true}))))]
      (is (= :let (:op root)))
      (is (= (atst/T s/Any) (:type root)))
      (is (atst/find-projected-node root #(= '(start G {:opt1 true}) (:form %))))
      (is (atst/find-projected-node root #(and (= :const (:op %))
                                          (at/map-type? (:type %))
                                          (= {:a 1 :b 2} (:form %))))))))

(deftest problematic-macroexpansion-analysis-test
  (testing "doto on a single call expands to let"
    (let [root (atst/project-ast (atst/analyze-form '(doto (set-cache-value 1))))]
      (is (= :let (:op root)))
      (is (= (atst/T s/Any) (:type root)))
      (is (atst/find-projected-node root #(= '(set-cache-value 1) (:form %))))))

  (testing "cond-> with equality check expands through let and if"
    (let [root (atst/project-ast (atst/analyze-form '(cond-> :invalid
                                             true
                                             (= :valid))))]
      (is (= :let (:op root)))
      (is (atst/find-projected-node root #(= :if (:op %))))
      (is (= (atst/T (sb/join s/Any s/Keyword)) (:type root)))))

  (testing "thread-first doto keeps incoming local"
    (let [root (atst/project-ast (atst/analyze-form '(-> cache
                                               (doto set-cache-value))
                                           (atst/locals 'cache)))]
      (is (= :let (:op root)))
      (is (= (atst/T s/Any) (:type root)))))

  (testing "cond-> with doto keeps expanded let branch"
    (let [root (atst/project-ast (atst/analyze-form '(cond-> :invalid
                                             true
                                             (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (atst/find-projected-node root #(= :if (:op %))))
      (is (= (atst/T s/Keyword) (:type root))))))

(deftest analyse-throw-test
  (testing "original throw setup"
    (let [root (atst/project-ast (atst/analyze-form '(throw (UnsupportedOperationException. "oops, not done yet"))))]
      (is (= :throw (:op root)))
      (is (= at/BottomType (:type root)))
      (is (= '(throw (UnsupportedOperationException. "oops, not done yet"))
             (:form root))))))

(deftest analyse-try-test
  (testing "original try/catch setup"
    (let [root (atst/project-ast (atst/analyze-form '(try (+ 1 2)
                                                (catch UnsupportedOperationException e
                                                  (println "doesn't work")))))]
      (is (= :try (:op root)))
      (is (= :static-call (:op (atst/child-projection root :body))))
      (is (= '(println "doesn't work")
             (:form (atst/child-projection (first (atst/child-projection root :catches)) :body))))))
  (testing "original try/catch/finally setup"
    (let [root (atst/project-ast (atst/analyze-form '(try (str 3)
                                                (+ 1 2)
                                                (catch UnsupportedOperationException e
                                                  (println "doesn't work")
                                                  (println "still doesn't"))
                                                (finally
                                                  (println "got something")
                                                  (+ 7 8)))))]
      (is (= :try (:op root)))
      (is (= :do (:op (atst/child-projection root :body))))
      (is (= :do (:op (atst/child-projection root :finally))))
      (is (atst/find-projected-node root #(= '(println "still doesn't") (:form %)))))))
