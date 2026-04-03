(ns skeptic.analysis.annotate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.analyzer.ast :as ana.ast]
            [schema.core :as s]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]
            [skeptic.test-examples :as test-examples]))

(deftest structural-analysis-test
  (testing "throw form"
    (let [root (atst/project-ast (atst/analyze-form '(throw (UnsupportedOperationException. "oops, not done yet"))))]
      (is (= :throw (:op root)))
      (is (= sb/Bottom (:schema root)))
      (is (= :new (:op (atst/child-projection root :exception))))
      (is (= '(new UnsupportedOperationException "oops, not done yet")
             (:form (atst/child-projection root :exception))))))

  (testing "try with catch"
    (let [root (atst/project-ast (atst/analyze-form '(try (+ 1 2)
                                                (catch UnsupportedOperationException e
                                                  (println "doesn't work")))))]
      (is (= :try (:op root)))
      (is (= [:body :catches] (mapv first (:children root))))
      (is (= :static-call (:op (atst/child-projection root :body))))
      (is (= :catch (:op (first (atst/child-projection root :catches)))))
      (is (= '(println "doesn't work")
             (:form (atst/child-projection (first (atst/child-projection root :catches)) :body))))))

  (testing "try with catch and finally"
    (let [root (atst/project-ast (atst/analyze-form '(try (str 3)
                                                (+ 1 2)
                                                (catch UnsupportedOperationException e
                                                  (println "doesn't work")
                                                  (println "still doesn't"))
                                                (finally
                                                  (println "got something")
                                                  (+ 7 8)))))]
      (is (= :try (:op root)))
      (is (= [:body :catches :finally] (mapv first (:children root))))
      (is (= :do (:op (atst/child-projection root :body))))
      (is (= :do (:op (atst/child-projection root :finally))))
      (is (= :catch (:op (first (atst/child-projection root :catches)))))))

  (testing "let forms"
    (let [empty-let (atst/project-ast (atst/analyze-form '(let [] (+ 1 2))))
          simple-let (atst/project-ast (atst/analyze-form '(let [x 1] (+ 1 x))))
          nested-let (atst/project-ast (atst/analyze-form '(let [x (+ 1 2)
                                                       y (+ 3 x)]
                                                   (+ 7 x)
                                                   (+ x y))))]
      (is (= :static-call (:op empty-let)))
      (is (= :let (:op simple-let)))
      (is (= :let (:op nested-let)))
      (is (= 1 (count (atst/child-projection simple-let :bindings))))
      (is (= 2 (count (atst/child-projection nested-let :bindings))))))

  (testing "if forms"
    (let [literal-if (atst/project-ast (atst/analyze-form '(if (even? 2) true "hello")))
          local-if (atst/project-ast (atst/analyze-form '(if (pos? x) 1 -1)
                                              (atst/locals 'x)))]
      (is (= :if (:op literal-if)))
      (is (= :if (:op local-if)))
      (is (= [:test :then :else] (mapv first (:children literal-if))))
      (is (= '(if (pos? x) 1 -1) (:form local-if)))))

  (testing "fn and def roots"
    (let [anon-fn (atst/project-ast (atst/analyze-form '(fn [x] x)))
          multi-fn (atst/project-ast (atst/analyze-form '(fn* ([x] (+ x 1))
                                                    ([x y] (+ x y)))))
          def-form (atst/project-ast (atst/analyze-form '(def n 5)))
          defn-form (atst/project-ast (atst/analyze-form '(defn f [x]
                                                  (println "something")
                                                  (+ 1 x))))]
      (is (= :fn (:op anon-fn)))
      (is (= :fn (:op multi-fn)))
      (is (= 1 (count (atst/child-projection anon-fn :methods))))
      (is (= 2 (count (atst/child-projection multi-fn :methods))))
      (is (= :def (:op def-form)))
      (is (= 'n (:name def-form)))
      (is (= :def (:op defn-form)))
      (is (= :with-meta (:op (atst/child-projection defn-form :init))))))

  (testing "literal collection roots"
    (let [vector-form (atst/project-ast (atst/analyze-form '[1 2 :a "hello"]))
          set-form (atst/project-ast (atst/analyze-form '#{1 2 :a "hello"}))
          map-form (atst/project-ast (atst/analyze-form '{:a 1 :b 2 :c 3}))
          nested-vector (atst/project-ast (atst/analyze-form '[1 2 [3 4 [5]]]))
          nested-map (atst/project-ast (atst/analyze-form '{:a 1 :b [:z "hello" #{1 2}]
                                                 :c {:d 7 :e {:f 9}}}))]
      (is (= :const (:op vector-form)))
      (is (at/vector-type? (ab/schema->type (:schema vector-form))))
      (is (= :const (:op set-form)))
      (is (at/set-type? (ab/schema->type (:schema set-form))))
      (is (= :const (:op map-form)))
      (is (at/map-type? (ab/schema->type (:schema map-form))))
      (is (= :const (:op nested-vector)))
      (is (= :const (:op nested-map)))))

  (testing "invalid literal-as-call form is rejected by the analyzer"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"is not a function"
         (atst/analyze-form '(1 2 :a "hello")))))

  (testing "non-literal collection nodes remain explicit analyzer ops"
    (let [vector-form (atst/project-ast (atst/analyze-form '(let [x 1] [x 2])))
          map-form (atst/project-ast (atst/analyze-form '(let [x 1] {:a x})))
          set-form (atst/project-ast (atst/analyze-form '(let [x 1] #{x 2})))]
      (is (= :vector (:op (atst/child-projection vector-form :body))))
      (is (= :map (:op (atst/child-projection map-form :body))))
      (is (= :set (:op (atst/child-projection set-form :body)))))))

(deftest schema-binding-and-flow-test
  (testing "let-driven flow"
    (let [empty-let (atst/project-ast (atst/analyze-form '(let [] (skeptic.test-examples/int-add 1 2))))
          bound-let (atst/project-ast (atst/analyze-form '(let [x (skeptic.test-examples/int-add 1 2)]
                                                  (skeptic.test-examples/int-add x 2))))]
      (is (= (atst/T s/Int) (:type empty-let)))
      (is (= (atst/T s/Int) (:type bound-let))))))

(deftest schema-functions-and-defs-test
  (testing "anonymous and named fn schemas"
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
                                                    ([x y] (+ x y)))))]
      (is (= s/Any (:output anon-fn)))
      (is (= [s/Int] (-> named-fn :arglists (get 1) :schema (->> (mapv :schema)))))
      (is (= s/Int (:output named-fn)))
      (is (= [s/Str s/Int] (-> named-binary-fn :arglists (get 2) :schema (->> (mapv :schema)))))
      (is (= s/Str (:output named-binary-fn)))
      (is (= 2 (count (:arglists multi-fn))))
      (is (= s/Any (:output body-fn)))))

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
      (is (= s/Int (:output fn-call)))
      (is (= (sb/variable s/Int) (:schema def-form)))
      (is (= 'f (:name defn-form)))
      (is (sb/variable? (:schema defn-form)))
      (is (= s/Int (-> typed-defn :schema :schema :output-schema)))
      (is (= s/Int (:schema do-form))))))

(deftest schema-try-and-throw-test
  (testing "try with throwing catch preserves body result"
    (let [root (atst/project-ast (atst/analyze-form '(try
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (throw e)))))]
      (is (= s/Int (:schema root)))
      (is (= sb/Bottom
             (:schema (atst/find-projected-node root #(= '(throw e) (:form %))))))))

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
      (is (= s/Int (:schema root)))
      (is (= :do (:op (atst/child-projection root :finally))))
      (is (atst/find-projected-node root #(= '(println "oops") (:form %)))))))

(deftest misc-and-macro-edge-cases-test
  (testing "nested bad call remains analyzable"
    (let [root (atst/project-ast (atst/analyze-form '(defn sample-bad-fn
                                             [x]
                                             (skeptic.test-examples/int-add
                                              1
                                              (skeptic.test-examples/int-add nil x)))))]
      (is (sb/variable? (:schema root)))
      (is (atst/find-projected-node root #(= '(skeptic.test-examples/int-add nil x) (:form %))))
      (is (atst/find-projected-node root #(= '(skeptic.test-examples/int-add 1 (skeptic.test-examples/int-add nil x))
                                        (:form %))))))

  (testing "fn once metadata is preserved in analyzer form"
    (let [root (atst/project-ast (atst/analyze-form '(defn sample-fn-once
                                             [x]
                                             ((^{:once true} fn* [y] (int-add y nil))
                                              x))
                                           (atst/locals 'int-add)))]
      (is (sb/variable? (:schema root)))
      (is (atst/find-projected-node root #(= :with-meta (:op %))))))

  (testing "local callable from let-bound value stays conservative"
    (let [root (atst/project-ast (atst/analyze-form '(defn sample-path-fn
                                             [x]
                                             (let [f (+ 1 x)]
                                               (f x)))))]
      (is (sb/variable? (:schema root)))
      (is (atst/find-projected-node root #(= '(f x) (:form %))))))

  (testing "doto expansion keeps literal map schema"
    (let [root (atst/project-ast (atst/analyze-form '(doto (make-component {:a 1 :b 2})
                                             (start {:opt1 true}))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))
      (is (atst/find-projected-node root #(= '(start G {:opt1 true}) (:form %))))
      (is (atst/find-projected-node root #(and (= :const (:op %))
                                          (at/map-type? (ab/schema->type (:schema %)))
                                          (= {:a 1 :b 2} (:form %))))))))

(deftest problematic-macroexpansion-analysis-test
  (testing "doto on a single call expands to let"
    (let [root (atst/project-ast (atst/analyze-form '(doto (set-cache-value 1))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))
      (is (atst/find-projected-node root #(= '(set-cache-value 1) (:form %))))))

  (testing "cond-> with equality check expands through let and if"
    (let [root (atst/project-ast (atst/analyze-form '(cond-> :invalid
                                             true
                                             (= :valid))))]
      (is (= :let (:op root)))
      (is (atst/find-projected-node root #(= :if (:op %))))
      (is (= (sb/join s/Any s/Keyword) (:schema root)))))

  (testing "thread-first doto keeps incoming local"
    (let [root (atst/project-ast (atst/analyze-form '(-> cache
                                               (doto set-cache-value))
                                           (atst/locals 'cache)))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))))

  (testing "cond-> with doto keeps expanded let branch"
    (let [root (atst/project-ast (atst/analyze-form '(cond-> :invalid
                                             true
                                             (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (atst/find-projected-node root #(= :if (:op %))))
      (is (= s/Keyword (:schema root))))))

(deftest analyse-throw-test
  (testing "original throw setup"
    (let [root (atst/project-ast (atst/analyze-form '(throw (UnsupportedOperationException. "oops, not done yet"))))]
      (is (= :throw (:op root)))
      (is (= sb/Bottom (:schema root)))
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

(deftest analyse-let-test
  (testing "original empty let setup"
    (let [root (atst/project-ast (atst/analyze-form '(let [] (+ 1 2))))]
      (is (= :static-call (:op root)))
      (is (= s/Any (:schema root)))))
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
      (is (= s/Any (:output root)))))
  (testing "original named identity fn setup"
    (let [root (atst/project-ast (atst/analyze-form atst/sample-dict
                                          '(fn [x] x)
                                          {:name 'f}))]
      (is (= :fn (:op root)))
      (is (= [s/Int] (-> root :arglists (get 1) :schema (->> (mapv :schema)))))
      (is (= s/Int (:output root)))))
  (testing "original named binary fn setup"
    (let [root (atst/project-ast (atst/analyze-form atst/sample-dict
                                          '(fn [y z] y)
                                          {:name 'f}))]
      (is (= :fn (:op root)))
      (is (= [s/Str s/Int] (-> root :arglists (get 2) :schema (->> (mapv :schema)))))
      (is (= s/Str (:output root)))))
  (testing "original multi-expression fn setup"
    (let [root (atst/project-ast (atst/analyze-form '(fn [x y]
                                             (str y x)
                                             (+ x y))))]
      (is (= :fn (:op root)))
      (is (= s/Any (:output root)))
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
      (is (= (sb/variable s/Int) (:schema root)))))
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
      (is (at/vector-type? (ab/schema->type (:schema root))))))
  (testing "original set literal setup"
    (let [root (atst/project-ast (atst/analyze-form '#{1 2 :a "hello"}))]
      (is (= :const (:op root)))
      (is (at/set-type? (ab/schema->type (:schema root))))))
  (testing "original list literal setup"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"is not a function"
         (atst/analyze-form '(1 2 :a "hello")))))
  (testing "original map literal setup"
    (let [root (atst/project-ast (atst/analyze-form '{:a 1 :b 2 :c 3}))]
      (is (= :const (:op root)))
      (is (at/map-type? (ab/schema->type (:schema root))))))
  (testing "original nested vector setup"
    (let [root (atst/project-ast (atst/analyze-form '[1 2 [3 4 [5]]]))]
      (is (= :const (:op root)))
      (is (at/vector-type? (ab/schema->type (:schema root))))))
  (testing "original nested map setup"
    (let [root (atst/project-ast (atst/analyze-form '{:a 1 :b [:z "hello" #{1 2}]
                                           :c {:d 7 :e {:f 9}}}))]
      (is (= :const (:op root)))
      (is (at/map-type? (ab/schema->type (:schema root)))))))

(deftest attach-schema-info-let-test
  (testing "original empty let schema setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(let [] (skeptic.test-examples/int-add 1 2))))]
      (is (= s/Int (:schema root)))))
  (testing "original bound let schema setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(let [x (skeptic.test-examples/int-add 1 2)]
                                             (skeptic.test-examples/int-add x 2))))]
      (is (= :let (:op root)))
      (is (= s/Int (:schema root))))))

(deftest attach-schema-info-fn-test
  (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                        '(fn [x] (skeptic.test-examples/int-add 1 2))))]
    (is (= :fn (:op root)))
    (is (= (atst/T s/Int) (:output-type root)))
    (is (= [(atst/T s/Any)] (atst/arglist-types root 1)))))

(deftest attach-schema-info-def-test
  (testing "original def schema setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(def n 5)))]
      (is (= (sb/variable s/Int) (:schema root)))))
  (testing "original defn schema setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(defn f [x y]
                                             (println "something")
                                             (skeptic.test-examples/int-add x y))))]
      (is (sb/variable? (:schema root)))
      (is (= s/Int (-> root :schema :schema :output-schema)))))
  (testing "original typed defn schema setup"
    (let [root (atst/project-ast (atst/analyze-form atst/sample-dict
                                          '(defn f [y z]
                                             (println y)
                                             z)))]
      (is (sb/variable? (:schema root)))
      (is (= s/Int (-> root :schema :schema :output-schema))))))

(deftest attach-schema-info-do-test
  (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                        '(do
                                           (println "something")
                                           (skeptic.test-examples/int-add x y))))]
    (is (= :do (:op root)))
    (is (= s/Int (:schema root)))))

(deftest attach-schema-info-try-throw-test
  (testing "original try/throw schema setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(try
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (throw e)))))]
      (is (= s/Int (:schema root)))
      (is (= sb/Bottom
             (:schema (atst/find-projected-node root #(= '(throw e) (:form %))))))))
  (testing "original try/catch/finally schema setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(try
                                             (clojure.core/str "hello")
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (println "oops")
                                               (throw e))
                                             (finally
                                               (skeptic.test-examples/int-add 3 4)
                                               (clojure.core/str "world")))))]
      (is (= s/Int (:schema root)))
      (is (= :do (:op (atst/child-projection root :finally))))
      (is (atst/find-projected-node root #(= '(println "oops") (:form %)))))))

(deftest attach-schema-info-misc-tests
  (testing "original nested bad fn setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(defn sample-bad-fn
                                             [x]
                                             (skeptic.test-examples/int-add
                                              1
                                              (skeptic.test-examples/int-add nil x)))))]
      (is (sb/variable? (:schema root)))
      (is (atst/find-projected-node root #(= '(skeptic.test-examples/int-add nil x) (:form %))))
      (is (atst/find-projected-node root #(= '(skeptic.test-examples/int-add 1 (skeptic.test-examples/int-add nil x))
                                        (:form %))))))
  (testing "original fn-once setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(defn sample-fn-once
                                             [x]
                                             ((^{:once true} fn* [y] (int-add y nil))
                                              x))))]
      (is (sb/variable? (:schema root)))
      (is (atst/find-projected-node root #(= :with-meta (:op %))))))
  (testing "original sample-path-fn setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(defn sample-path-fn
                                             [x]
                                             (let [f (+ 1 x)]
                                               (f x)))))]
      (is (sb/variable? (:schema root)))
      (is (atst/find-projected-node root #(= '(f x) (:form %))))))
  (testing "original doto component setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(doto (make-component {:a 1 :b 2})
                                             (start {:opt1 true}))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))
      (is (atst/find-projected-node root #(= '(start G {:opt1 true}) (:form %))))
      (is (atst/find-projected-node root #(and (= :const (:op %))
                                          (at/map-type? (ab/schema->type (:schema %)))
                                          (= {:a 1 :b 2} (:form %))))))))

(deftest analyse-problematic-let-test
  (testing "original single-call doto setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(doto (set-cache-value 1))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))
      (is (atst/find-projected-node root #(= '(set-cache-value 1) (:form %))))))
  (testing "original cond-> equality setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(cond-> :invalid
                                             true
                                             (= :valid))))]
      (is (= :let (:op root)))
      (is (atst/find-projected-node root #(= :if (:op %))))
      (is (= (sb/join s/Any s/Keyword) (:schema root)))))
  (testing "original thread-first doto setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(-> cache
                                               (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))))
  (testing "original cond-> doto setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                          '(cond-> :invalid
                                             true
                                             (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (atst/find-projected-node root #(= :if (:op %))))
      (is (= s/Keyword (:schema root))))))
(deftest ordinary-analysis-remains-first-order-test
  (let [ast (aa/attach-schema-info-loop {}
                                        '(let [id (fn [x] x)]
                                           (id 1))
                                        {:ns 'skeptic.analysis.annotate-test})
        node-types (keep :type (ana.ast/nodes ast))]
    (is (seq node-types))
    (is (not-any? at/forall-type? node-types))
    (is (not-any? at/type-var-type? node-types))
    (is (not-any? at/sealed-dyn-type? node-types))))
