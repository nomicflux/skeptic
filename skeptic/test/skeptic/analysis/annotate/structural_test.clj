(ns skeptic.analysis.annotate.structural-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.cast :as cast]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.native-fns :as anf]
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
      (is (atst/find-projected-node root #(= :recur (:op %))))))

  (testing "for expression is typed as a homogeneous seq of the body type"
    (let [root (atst/project-ast
                (atst/analyze-form atst/typed-test-examples-dict
                                   '(for [x [1 2 3]] (skeptic.test-examples/int-add x 0))))]
      (is (at/seq-type? (:type root)))
      (is (:homogeneous? (:type root)))
      (is (= (atst/T s/Int) (first (:items (:type root))))))))

(deftest native-inc-annotates-via-dict-test
  (testing "clojure.core/inc static-call yields JVM Number (Numbers/inc)"
    (let [root (atst/project-ast (atst/analyze-form {} '(inc 1)))]
      (is (= atst/num-ground (:type root))))))

(deftest native-core-numbers-and-dict-smoke-test
  (testing "Numbers static-call dec and nested +"
    (is (= atst/num-ground (:type (atst/project-ast (atst/analyze-form {} '(dec 1))))))
    (let [form '(+ 1 2 3)
          raw (aa/annotate-form-loop {} form {:ns 'skeptic.analysis-test})
          root (atst/project-ast raw)]
      (is (= :static-call (:op root)))
      (is (= 'add (:method root)))
      (is (= :static-call (:op (first (:args raw)))))
      (is (= atst/num-ground (:type root)))))
  (testing "invoke + arities from native-fn-dict"
    (is (= atst/num-ground (:type (atst/project-ast (atst/analyze-form {} '(+))))))
    (is (= atst/num-ground (:type (atst/project-ast (atst/analyze-form {} '(+ 1)))))))
  (testing "native-fn-dict + shape"
    (let [e (get anf/native-fn-dict 'clojure.core/+)]
      (is (every? #(contains? (:arglists e) %) [0 1 2 :varargs]))
      (is (= 3 (get-in e [:arglists :varargs :count])))
      (is (= 3 (count (get-in e [:arglists :varargs :types]))))))
  (testing "adversarial: even? expects int arg type — Str actual does not cast"
    (let [root (atst/project-ast (atst/analyze-form {} '(even? "a")))]
      (is (= (atst/T s/Int) (first (:expected-argtypes root))))
      (is (not (:ok? (cast/check-cast (atst/T s/Str) (first (:expected-argtypes root)))))))))

(deftest native-seq-concat-and-tuple-adversarial-test
  (testing "concat joins element types (not first collection only)"
    (let [root (atst/project-ast (atst/analyze-form {} '(concat [1] ["a"])))]
      (is (at/seq-type? (:type root)))
      (is (:homogeneous? (:type root)))
      (let [e (first (:items (:type root)))]
        (is (at/union-type? e))
        (is (= 2 (count (:members e)))))))
  (testing "tuple first: slot type Int; fails cast to Str alone"
    (let [root (atst/project-ast (atst/analyze-form {} '(first [1 "a"])))]
      (is (= (atst/T s/Int) (:type root)))
      (is (:ok? (cast/check-cast (:type root) (atst/T s/Int))))
      (is (not (:ok? (cast/check-cast (:type root) (atst/T s/Str))))))))
