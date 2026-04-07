(ns skeptic.analysis.annotate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.analyzer.ast :as ana.ast]
            [schema.core :as s]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.cast :as cast]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.value :as av]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]))

(deftest structural-analysis-test
  (testing "throw form"
    (let [root (atst/project-ast (atst/analyze-form '(throw (UnsupportedOperationException. "oops, not done yet"))))]
      (is (= :throw (:op root)))
      (is (= at/BottomType (:type root)))
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

  (testing "case narrows discriminant and joins branch types"
    (let [kw (s/cond-pre (s/eq :a) (s/eq :b))
          root (atst/project-ast
                (atst/analyze-form '(case x :a 1 :b 2)
                                   (merge (atst/local-types {'x {:type (atst/T kw)}})
                                          {:ns 'skeptic.analysis-test})))
          case-node (first (filter #(= :case (:op %)) (atst/projected-nodes root)))]
      (is (some? case-node) "tools.analyzer should emit :case under macro expansion")
      (is (= (atst/T s/Int) (:type case-node)))))

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
      (is (at/vector-type? (:type vector-form)))
      (is (= :const (:op set-form)))
      (is (at/set-type? (:type set-form)))
      (is (= :const (:op map-form)))
      (is (at/map-type? (:type map-form)))
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
      (is (= :set (:op (atst/child-projection set-form :body))))))

  (testing "loop and recur"
    (let [simple (atst/project-ast (atst/analyze-form '(loop [x 1] x)))
          with-recur (atst/project-ast
                      (atst/analyze-form '(loop [x 0]
                                           (if (clojure.core/< x 3)
                                             (recur (clojure.core/inc x))
                                             x))))]
      (is (= :loop (:op simple)))
      (is (= [:bindings :body] (mapv first (:children simple))))
      (is (= 1 (count (atst/child-projection simple :bindings))))
      (is (= (atst/T s/Int) (:type simple)))
      (is (= :loop (:op with-recur)))
      (is (= (atst/T s/Int) (:type with-recur)))
      (is (some? (atst/find-projected-node with-recur #(= :recur (:op %)))))
      (is (= at/BottomType
             (:type (atst/find-projected-node with-recur #(= :recur (:op %))))))))

  (testing "loop body vector/map literals cast to declared schemas [s/Int] and {:a s/Str :b [s/Int]}"
    (let [vec-loop (atst/project-ast (atst/analyze-form '(loop [] [1 2 3])))
          map-loop (atst/project-ast (atst/analyze-form '(loop [] {:a "hi" :b [1 2]})))]
      (is (= :loop (:op vec-loop)))
      (is (at/vector-type? (:type vec-loop)))
      (is (:ok? (cast/check-cast (:type vec-loop) (atst/T [s/Int]))))
      (is (= :loop (:op map-loop)))
      (is (at/map-type? (:type map-loop)))
      (is (:ok? (cast/check-cast (:type map-loop) (atst/T {:a s/Str :b [s/Int]}))))))

  (testing "for macro expands to loop/recur in the analyzer AST (structural only)"
    (let [root (atst/project-ast
                (atst/analyze-form '(for [x [1 2]] (skeptic.test-examples/int-add x 0))))]
      (is (atst/find-projected-node root #(= :loop (:op %))))
      (is (atst/find-projected-node root #(= :recur (:op %)))))))

(deftest native-inc-annotates-via-dict-test
  (testing "clojure.core/inc gets Int output from native fn dict"
    (let [root (atst/project-ast (atst/analyze-form {} '(inc 1)))]
      (is (= (atst/T s/Int) (:type root))))))

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
      (is (= (atst/T s/Any) (:output-type body-fn)))))

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

(deftest analyse-let-test
  (testing "original empty let setup"
    (let [root (atst/project-ast (atst/analyze-form '(let [] (+ 1 2))))]
      (is (= :static-call (:op root)))
      (is (= (atst/T s/Any) (:type root)))))
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
      (is (= at/Dyn (:output-type root)))
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

(deftest attach-type-info-let-test
  (testing "original empty let typed setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(let [] (skeptic.test-examples/int-add 1 2))))]
      (is (= (atst/T s/Int) (:type root)))))
  (testing "original bound let typed setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(let [x (skeptic.test-examples/int-add 1 2)]
                                             (skeptic.test-examples/int-add x 2))))]
      (is (= :let (:op root)))
      (is (= (atst/T s/Int) (:type root))))))

(deftest attach-type-info-fn-test
  (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                        '(fn [x] (skeptic.test-examples/int-add 1 2))))]
    (is (= :fn (:op root)))
    (is (= (atst/T s/Int) (:output-type root)))
    (is (= [(atst/T s/Any)] (atst/arglist-types root 1)))))

(deftest attach-type-info-def-test
  (testing "original def typed setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(def n 5)))]
      (is (= (at/->VarT (atst/T s/Int)) (:type root)))))
  (testing "original defn typed setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(defn f [x y]
                                             (println "something")
                                             (skeptic.test-examples/int-add x y))))]
      (is (at/var-type? (:type root)))
      (is (= (atst/T s/Int)
             (:output-type (atst/child-projection (atst/child-projection root :init) :expr)))))
    )
  (testing "original typed defn setup"
    (let [root (atst/project-ast (atst/analyze-form atst/sample-dict
                                          '(defn f [y z]
                                             (println y)
                                             z)))]
      (is (at/var-type? (:type root)))
      (is (= (atst/T s/Int)
             (:output-type (atst/child-projection (atst/child-projection root :init) :expr))))))
  )

(deftest attach-type-info-do-test
  (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                        '(do
                                           (println "something")
                                           (skeptic.test-examples/int-add x y))))]
    (is (= :do (:op root)))
    (is (= (atst/T s/Int) (:type root)))))

(deftest attach-type-info-try-throw-test
  (testing "original try/throw typed setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(try
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (throw e)))))]
      (is (= (atst/T s/Int) (:type root)))
      (is (= at/BottomType
             (:type (atst/find-projected-node root #(= '(throw e) (:form %))))))))
  (testing "original try/catch/finally typed setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(try
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

(deftest attach-type-info-misc-tests
  (testing "original nested bad fn setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(defn sample-bad-fn
                                             [x]
                                             (skeptic.test-examples/int-add
                                              1
                                              (skeptic.test-examples/int-add nil x)))))]
      (is (at/var-type? (:type root)))
      (is (atst/find-projected-node root #(= '(skeptic.test-examples/int-add nil x) (:form %))))
      (is (atst/find-projected-node root #(= '(skeptic.test-examples/int-add 1 (skeptic.test-examples/int-add nil x))
                                        (:form %))))))
  (testing "original fn-once setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(defn sample-fn-once
                                             [x]
                                             ((^{:once true} fn* [y] (int-add y nil))
                                              x))))]
      (is (at/var-type? (:type root)))
      (is (atst/find-projected-node root #(= :with-meta (:op %))))))
  (testing "original sample-path-fn setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(defn sample-path-fn
                                             [x]
                                             (let [f (+ 1 x)]
                                               (f x)))))]
      (is (at/var-type? (:type root)))
      (is (atst/find-projected-node root #(= '(f x) (:form %))))))
  (testing "original doto component setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(doto (make-component {:a 1 :b 2})
                                             (start {:opt1 true}))))]
      (is (= :let (:op root)))
      (is (= (atst/T s/Any) (:type root)))
      (is (atst/find-projected-node root #(= '(start G {:opt1 true}) (:form %))))
      (is (atst/find-projected-node root #(and (= :const (:op %))
                                          (at/map-type? (:type %))
                                          (= {:a 1 :b 2} (:form %))))))))

(deftest analyse-problematic-let-test
  (testing "original single-call doto setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(doto (set-cache-value 1))))]
      (is (= :let (:op root)))
      (is (= (atst/T s/Any) (:type root)))
      (is (atst/find-projected-node root #(= '(set-cache-value 1) (:form %))))))
  (testing "original cond-> equality setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(cond-> :invalid
                                             true
                                             (= :valid))))]
      (is (= :let (:op root)))
      (is (atst/find-projected-node root #(= :if (:op %))))
      (is (= (atst/T (sb/join s/Any s/Keyword)) (:type root)))))
  (testing "original thread-first doto setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(-> cache
                                               (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (= (atst/T s/Any) (:type root)))))
  (testing "original cond-> doto setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                          '(cond-> :invalid
                                             true
                                             (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (atst/find-projected-node root #(= :if (:op %))))
      (is (= (atst/T s/Keyword) (:type root))))))
(deftest ordinary-analysis-remains-first-order-test
  (let [ast (aa/annotate-form-loop {}
                                   '(let [id (fn [x] x)]
                                      (id 1))
                                   {:ns 'skeptic.analysis.annotate-test})
        node-types (keep :type (ana.ast/nodes ast))]
    (is (seq node-types))
    (is (not-any? at/forall-type? node-types))
    (is (not-any? at/type-var-type? node-types))
    (is (not-any? at/sealed-dyn-type? node-types))))

(deftest annotated-ast-omits-schema-mirror-fields-test
  (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                                  '(defn f [x y]
                                                     (skeptic.test-examples/int-add x y))))
        init-expr (atst/child-projection (atst/child-projection root :init) :expr)
        method (first (atst/child-projection init-expr :methods))]
    (is (contains? init-expr :type))
    (is (contains? init-expr :output-type))
    (is (not (contains? init-expr :schema)))
    (is (not (contains? init-expr :output)))
    (is (not (contains? init-expr :expected-arglist)))
    (is (not (contains? init-expr :actual-arglist)))
    (is (contains? method :type))
    (is (not (contains? method :schema)))
    (is (not (contains? method :output)))
    (is (not (contains? method :expected-arglist)))
    (is (not (contains? method :actual-arglist)))
    (is (contains? method :param-specs))
    (is (vector? (:param-specs method)))))
