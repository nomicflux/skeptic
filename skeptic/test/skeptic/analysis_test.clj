(ns skeptic.analysis-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.walk :as walk]
            [schema.core :as s]
            [skeptic.analysis :as sut]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.schema :as as]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.checking :as checking]
            [skeptic.examples]
            [skeptic.schematize :as schematize]
            [skeptic.static-call-examples]
            [skeptic.test-examples :as test-examples])
  (:import [java.io File]))

(defn set-cache-value
  [& _args]
  nil)

(defn f
  [& _args]
  nil)

(defn int-add
  [& _args]
  nil)

(defn make-component
  [& _args]
  nil)

(defn start
  [& _args]
  nil)

(def x nil)
(def y nil)
(def z nil)
(def cache nil)

(defn T
  [schema]
  (ab/schema->type schema))

(defn arg-entry
  [[name schema]]
  {:type (T schema)
   :optional? false
   :name name})

(defn arity-entry
  [args]
  {:arglist (mapv first args)
   :count (count args)
   :types (mapv arg-entry args)})

(defn fn-entry
  [sym output & arities]
  (let [fn-schema (s/make-fn-schema output
                                    (mapv (fn [args]
                                            (mapv (fn [[name schema]]
                                                    (s/one schema name))
                                                  args))
                                          arities))]
    {:name (str sym)
     :type (T fn-schema)
     :output-type (T output)
     :arglists (into {}
                     (map (fn [args]
                            [(count args) (arity-entry args)]))
                     arities)}))

(def analysis-dict
  (merge test-examples/sample-dict
         {'skeptic.analysis-test/f
          (fn-entry 'skeptic.analysis-test/f s/Any [['value s/Any]])
          'skeptic.analysis-test/int-add
          (fn-entry 'skeptic.analysis-test/int-add s/Any [['left s/Any]
                                                          ['right s/Any]])}
         {'skeptic.analysis-test/set-cache-value
          (fn-entry 'skeptic.analysis-test/set-cache-value s/Any [['value s/Any]])
          'skeptic.analysis-test/make-component
          (fn-entry 'skeptic.analysis-test/make-component s/Any [['opts s/Any]])
          'skeptic.analysis-test/start
          (fn-entry 'skeptic.analysis-test/start s/Any [['component s/Any]
                                                        ['opts s/Any]])}))

(def sample-dict
  {'f
   {:name "f"
    :type (T (s/=> s/Int s/Int))
    :output-type (T s/Int)
    :arglists {1 {:arglist ['x]
                   :count 1
                   :types [{:type (T s/Int) :optional? false :name 'x}]}
               2 {:arglist ['y 'z]
                  :count 2
                  :types [{:type (T s/Str) :optional? false :name 'y}
                          {:type (T s/Int) :optional? false :name 'z}]}}}})

(def static-call-dict
  (merge (schematize/ns-schemas {} 'skeptic.static-call-examples)
         {'user {:type (T skeptic.static-call-examples/UserDesc)}
          'counts {:type (T skeptic.static-call-examples/MaybeCount)}
          'left {:type (T skeptic.static-call-examples/LeftFields)}
          'right {:type (T skeptic.static-call-examples/RightFields)}}))

(def test-examples-file (File. "test/skeptic/test_examples.clj"))
(def examples-file (File. "src/skeptic/examples.clj"))
(def static-call-examples-file (File. "src/skeptic/static_call_examples.clj"))

(defn locals
  [& syms]
  {:locals (into {}
                 (map (fn [sym] [sym (T s/Any)]))
                 syms)})

(defn local-schemas
  [m]
  {:locals m})

(defn analyze-form
  ([form]
   (sut/attach-schema-info-loop analysis-dict form {:ns 'skeptic.analysis-test}))
  ([arg1 arg2]
   (if (map? arg1)
     (sut/attach-schema-info-loop arg1 arg2 {:ns 'skeptic.analysis-test})
     (sut/attach-schema-info-loop analysis-dict arg1 (merge {:ns 'skeptic.analysis-test}
                                                            arg2))))
  ([dict form opts]
   (sut/attach-schema-info-loop dict form (merge {:ns 'skeptic.analysis-test}
                                                 opts))))

(defn normalize-symbol
  [value]
  (if (symbol? value)
    (let [name-part (name value)]
      (if (str/includes? name-part "__")
        (symbol (namespace value)
                (first (str/split name-part #"__")))
        value))
    value))

(defn normalize-form
  [form]
  (walk/postwalk normalize-symbol form))

(defn var->sym
  [value]
  (when (instance? clojure.lang.Var value)
    (let [m (meta value)]
      (symbol (str (ns-name (:ns m)) "/" (:name m))))))

(def stable-keys
  [:op :form :body? :local :arg-id :variadic? :class :method :validated?
   :literal? :type :output-type :fn-type :types :arglist :arglists
   :actual-argtypes :expected-argtypes :schema :output :actual-arglist
   :expected-arglist :raw-forms])

(defn arglist-types
  [root arity]
  (-> root :arglists (get arity) :types (->> (mapv :type))))

(declare project-node)

(defn project-children
  [node]
  (mapv (fn [key]
          [key (project-node (get node key))])
        (:children node)))

(defn project-node
  [node]
  (cond
    (nil? node) nil
    (vector? node) (mapv project-node node)
    (map? node)
    (let [base (cond-> (select-keys node stable-keys)
                 (:form node) (update :form normalize-form)
                 (:raw-forms node) (update :raw-forms #(mapv normalize-form %))
                 (and (= :def (:op node)) (:name node)) (assoc :name (:name node))
                 (#{:var :the-var} (:op node)) (assoc :resolved-var (var->sym (:var node)))
                 (:children node) (assoc :children (project-children node)))]
      (into {}
            (remove (comp nil? val))
            base))
    :else node))

(defn project-ast
  [root]
  (project-node root))

(defn projected-nodes
  [root]
  (letfn [(walk-projected [node]
            (lazy-seq
             (cond
               (nil? node) nil
               (vector? node) (mapcat walk-projected node)
               (map? node) (cons node
                                 (mapcat (comp walk-projected second)
                                         (:children node)))
               :else nil)))]
    (walk-projected root)))

(defn find-projected-node
  [root pred]
  (some #(when (pred %) %) (projected-nodes root)))

(defn child-projection
  [node key]
  (->> (:children node)
       (some (fn [[child-key child]]
               (when (= child-key key) child)))))

(defn ast-by-name
  [asts sym]
  (some #(when (= sym (:name %)) %) asts))

(defn node-by-form
  [ast form]
  (some #(when (= form (:form %)) %) (ana.ast/nodes ast)))

(defmacro source-exprs-in
  [ns-sym file]
  `(checking/block-in-ns ~ns-sym ~file
                         (checking/ns-exprs ~file)))

(deftest structural-analysis-test
  (testing "throw form"
    (let [root (project-ast (analyze-form '(throw (UnsupportedOperationException. "oops, not done yet"))))]
      (is (= :throw (:op root)))
      (is (= sb/Bottom (:schema root)))
      (is (= :new (:op (child-projection root :exception))))
      (is (= '(new UnsupportedOperationException "oops, not done yet")
             (:form (child-projection root :exception))))))

  (testing "try with catch"
    (let [root (project-ast (analyze-form '(try (+ 1 2)
                                                (catch UnsupportedOperationException e
                                                  (println "doesn't work")))))]
      (is (= :try (:op root)))
      (is (= [:body :catches] (mapv first (:children root))))
      (is (= :static-call (:op (child-projection root :body))))
      (is (= :catch (:op (first (child-projection root :catches)))))
      (is (= '(println "doesn't work")
             (:form (child-projection (first (child-projection root :catches)) :body))))))

  (testing "try with catch and finally"
    (let [root (project-ast (analyze-form '(try (str 3)
                                                (+ 1 2)
                                                (catch UnsupportedOperationException e
                                                  (println "doesn't work")
                                                  (println "still doesn't"))
                                                (finally
                                                  (println "got something")
                                                  (+ 7 8)))))]
      (is (= :try (:op root)))
      (is (= [:body :catches :finally] (mapv first (:children root))))
      (is (= :do (:op (child-projection root :body))))
      (is (= :do (:op (child-projection root :finally))))
      (is (= :catch (:op (first (child-projection root :catches)))))))

  (testing "let forms"
    (let [empty-let (project-ast (analyze-form '(let [] (+ 1 2))))
          simple-let (project-ast (analyze-form '(let [x 1] (+ 1 x))))
          nested-let (project-ast (analyze-form '(let [x (+ 1 2)
                                                       y (+ 3 x)]
                                                   (+ 7 x)
                                                   (+ x y))))]
      (is (= :static-call (:op empty-let)))
      (is (= :let (:op simple-let)))
      (is (= :let (:op nested-let)))
      (is (= 1 (count (child-projection simple-let :bindings))))
      (is (= 2 (count (child-projection nested-let :bindings))))))

  (testing "if forms"
    (let [literal-if (project-ast (analyze-form '(if (even? 2) true "hello")))
          local-if (project-ast (analyze-form '(if (pos? x) 1 -1)
                                              (locals 'x)))]
      (is (= :if (:op literal-if)))
      (is (= :if (:op local-if)))
      (is (= [:test :then :else] (mapv first (:children literal-if))))
      (is (= '(if (pos? x) 1 -1) (:form local-if)))))

  (testing "fn and def roots"
    (let [anon-fn (project-ast (analyze-form '(fn [x] x)))
          multi-fn (project-ast (analyze-form '(fn* ([x] (+ x 1))
                                                    ([x y] (+ x y)))))
          def-form (project-ast (analyze-form '(def n 5)))
          defn-form (project-ast (analyze-form '(defn f [x]
                                                  (println "something")
                                                  (+ 1 x))))]
      (is (= :fn (:op anon-fn)))
      (is (= :fn (:op multi-fn)))
      (is (= 1 (count (child-projection anon-fn :methods))))
      (is (= 2 (count (child-projection multi-fn :methods))))
      (is (= :def (:op def-form)))
      (is (= 'n (:name def-form)))
      (is (= :def (:op defn-form)))
      (is (= :with-meta (:op (child-projection defn-form :init))))))

  (testing "do, application, and literal collection roots"
    (let [do-form (project-ast (analyze-form '(do (str "hello") (+ 1 2))))
          plus-form (project-ast (analyze-form '(+ 1 x) (locals 'x)))
          local-invoke (project-ast (analyze-form '(f)
                                                  (local-schemas {'f (s/make-fn-schema s/Int [[]])})))
          nested-invoke (project-ast (analyze-form '((f 1) 3 4)
                                                   (locals 'f)))
          vector-form (project-ast (analyze-form '[1 2 :a "hello"]))
          set-form (project-ast (analyze-form '#{1 2 :a "hello"}))
          map-form (project-ast (analyze-form '{:a 1 :b 2 :c 3}))
          nested-vector (project-ast (analyze-form '[1 2 [3 4 [5]]]))
          nested-map (project-ast (analyze-form '{:a 1 :b [:z "hello" #{1 2}]
                                                 :c {:d 7 :e {:f 9}}}))]
      (is (= :do (:op do-form)))
      (is (= :static-call (:op plus-form)))
      (is (= :invoke (:op local-invoke)))
      (is (= :invoke (:op nested-invoke)))
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
         (analyze-form '(1 2 :a "hello")))))

  (testing "non-literal collection nodes remain explicit analyzer ops"
    (let [vector-form (project-ast (analyze-form '(let [x 1] [x 2])))
          map-form (project-ast (analyze-form '(let [x 1] {:a x})))
          set-form (project-ast (analyze-form '(let [x 1] #{x 2})))]
      (is (= :vector (:op (child-projection vector-form :body))))
      (is (= :map (:op (child-projection map-form :body))))
      (is (= :set (:op (child-projection set-form :body)))))))

(deftest schema-values-and-collections-test
  (testing "scalar and literal collection schemas"
    (let [scalar (project-ast (analyze-form '1))
          empty-list (project-ast (analyze-form '()))
          vector-form (project-ast (analyze-form '[1 2]))
          nested-form (project-ast (analyze-form '[1 {:a 2 :b {:c #{3 4}}} 5]))]
      (is (= s/Int (:schema scalar)))
      (is (= [s/Any] (:schema empty-list)))
      (is (= [s/Int s/Int] (:schema vector-form)))
      (is (vector? (:schema nested-form)))
      (is (= 3 (count (:schema nested-form))))))

  (testing "application schemas"
    (let [dynamic-call (project-ast (analyze-form '(+ 1 2)))
          known-call (project-ast (analyze-form '(skeptic.test-examples/int-add 1 2)))]
      (is (= [(T s/Int) (T s/Int)] (:actual-argtypes dynamic-call)))
      (is (= (T s/Any) (:type dynamic-call)))
      (is (= [(T s/Int) (T s/Int)] (:actual-argtypes known-call)))
      (is (= [(T s/Int) (T s/Int)] (:expected-argtypes known-call)))
      (is (= (T s/Int) (:type known-call))))))

(deftest schema-binding-and-flow-test
  (testing "let-driven flow"
    (let [empty-let (project-ast (analyze-form '(let [] (skeptic.test-examples/int-add 1 2))))
          bound-let (project-ast (analyze-form '(let [x (skeptic.test-examples/int-add 1 2)]
                                                  (skeptic.test-examples/int-add x 2))))
          or-let (project-ast (analyze-form '(let [y nil
                                                   x (or y 1)]
                                               (skeptic.test-examples/int-add x 2))))]
      (is (= (T s/Int) (:type empty-let)))
      (is (= (T s/Int) (:type bound-let)))
      (is (= (T s/Int) (:type or-let)))
      (is (find-projected-node or-let #(and (= :if (:op %))
                                            (= (T (sb/join s/Any s/Int)) (:type %)))))))

  (testing "if refinement and joins"
    (let [literal-if (project-ast (analyze-form '(if (even? 2) true "hello")))
          local-if (project-ast (analyze-form '(if (pos? x) 1 -1)
                                              (locals 'x)))
          maybe-if (project-ast (analyze-form '(let [x nil] (if x x 1))))
          or-form (project-ast (analyze-form '(or nil 1)))]
      (is (= (T (sb/join s/Bool s/Str)) (:type literal-if)))
      (is (= (T s/Int) (:type local-if)))
      (is (= (T (sb/join s/Any s/Int)) (:type maybe-if)))
      (is (= :let (:op or-form)))
      (is (= (T (sb/join s/Any s/Int)) (:type or-form))))))

(deftest schema-functions-and-defs-test
  (testing "anonymous and named fn schemas"
    (let [anon-fn (project-ast (analyze-form '(fn [x] x)))
          named-fn (project-ast (analyze-form sample-dict
                                              '(fn [x] x)
                                              {:name 'f}))
          named-binary-fn (project-ast (analyze-form sample-dict
                                                     '(fn [y z] y)
                                                     {:name 'f}))
          body-fn (project-ast (analyze-form '(fn [x y]
                                                (str y x)
                                                (+ x y))))
          multi-fn (project-ast (analyze-form '(fn* ([x] (+ x 1))
                                                    ([x y] (+ x y)))))]
      (is (= s/Any (:output anon-fn)))
      (is (= [s/Int] (-> named-fn :arglists (get 1) :schema (->> (mapv :schema)))))
      (is (= s/Int (:output named-fn)))
      (is (= [s/Str s/Int] (-> named-binary-fn :arglists (get 2) :schema (->> (mapv :schema)))))
      (is (= s/Str (:output named-binary-fn)))
      (is (= 2 (count (:arglists multi-fn))))
      (is (= s/Any (:output body-fn)))))

  (testing "fn bodies, defs, and do blocks"
    (let [fn-call (project-ast (analyze-form '(fn [x]
                                                (skeptic.test-examples/int-add 1 2))))
          def-form (project-ast (analyze-form '(def n 5)))
          defn-form (project-ast (analyze-form '(defn f [x y]
                                                  (println "something")
                                                  (skeptic.test-examples/int-add x y))))
          typed-defn (project-ast (analyze-form sample-dict
                                                '(defn f [y z]
                                                   (println y)
                                                   z)))
          do-form (project-ast (analyze-form '(do
                                               (println "something")
                                               (skeptic.test-examples/int-add x y))
                                             (locals 'x 'y)))]
      (is (= s/Int (:output fn-call)))
      (is (= (sb/variable s/Int) (:schema def-form)))
      (is (= 'f (:name defn-form)))
      (is (sb/variable? (:schema defn-form)))
      (is (= s/Int (-> typed-defn :schema :schema :output-schema)))
      (is (= s/Int (:schema do-form))))))

(deftest schema-try-and-throw-test
  (testing "try with throwing catch preserves body result"
    (let [root (project-ast (analyze-form '(try
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (throw e)))))]
      (is (= s/Int (:schema root)))
      (is (= sb/Bottom
             (:schema (find-projected-node root #(= '(throw e) (:form %))))))))

  (testing "try with body, catch, and finally stays analyzable"
    (let [root (project-ast (analyze-form '(try
                                             (clojure.core/str "hello")
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (println "oops")
                                               (throw e))
                                             (finally
                                               (skeptic.test-examples/int-add 3 4)
                                               (clojure.core/str "world")))))]
      (is (= s/Int (:schema root)))
      (is (= :do (:op (child-projection root :finally))))
      (is (find-projected-node root #(= '(println "oops") (:form %)))))))

(deftest misc-and-macro-edge-cases-test
  (testing "nested bad call remains analyzable"
    (let [root (project-ast (analyze-form '(defn sample-bad-fn
                                             [x]
                                             (skeptic.test-examples/int-add
                                              1
                                              (skeptic.test-examples/int-add nil x)))))]
      (is (sb/variable? (:schema root)))
      (is (find-projected-node root #(= '(skeptic.test-examples/int-add nil x) (:form %))))
      (is (find-projected-node root #(= '(skeptic.test-examples/int-add 1 (skeptic.test-examples/int-add nil x))
                                        (:form %))))))

  (testing "local fn invocation keeps callable metadata"
    (let [root (project-ast (analyze-form '(let [f (fn [x] nil)]
                                             (skeptic.test-examples/int-add 1 (f x)))
                                           (locals 'x)))]
      (is (= s/Int (:schema root)))
      (is (= (s/maybe s/Any)
             (:schema (find-projected-node root #(= '(f x) (:form %))))))))

  (testing "fn once metadata is preserved in analyzer form"
    (let [root (project-ast (analyze-form '(defn sample-fn-once
                                             [x]
                                             ((^{:once true} fn* [y] (int-add y nil))
                                              x))
                                           (locals 'int-add)))]
      (is (sb/variable? (:schema root)))
      (is (find-projected-node root #(= :with-meta (:op %))))))

  (testing "local callable from let-bound value stays conservative"
    (let [root (project-ast (analyze-form '(defn sample-path-fn
                                             [x]
                                             (let [f (+ 1 x)]
                                               (f x)))))]
      (is (sb/variable? (:schema root)))
      (is (find-projected-node root #(= '(f x) (:form %))))))

  (testing "doto expansion keeps literal map schema"
    (let [root (project-ast (analyze-form '(doto (make-component {:a 1 :b 2})
                                             (start {:opt1 true}))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))
      (is (find-projected-node root #(= '(start G {:opt1 true}) (:form %))))
      (is (find-projected-node root #(and (= :const (:op %))
                                          (at/map-type? (ab/schema->type (:schema %)))
                                          (= {:a 1 :b 2} (:form %))))))))

(deftest problematic-macroexpansion-analysis-test
  (testing "doto on a single call expands to let"
    (let [root (project-ast (analyze-form '(doto (set-cache-value 1))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))
      (is (find-projected-node root #(= '(set-cache-value 1) (:form %))))))

  (testing "cond-> with equality check expands through let and if"
    (let [root (project-ast (analyze-form '(cond-> :invalid
                                             true
                                             (= :valid))))]
      (is (= :let (:op root)))
      (is (find-projected-node root #(= :if (:op %))))
      (is (= (sb/join s/Any s/Keyword) (:schema root)))))

  (testing "thread-first doto keeps incoming local"
    (let [root (project-ast (analyze-form '(-> cache
                                               (doto set-cache-value))
                                           (locals 'cache)))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))))

  (testing "cond-> with doto keeps expanded let branch"
    (let [root (project-ast (analyze-form '(cond-> :invalid
                                             true
                                             (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (find-projected-node root #(= :if (:op %))))
      (is (= s/Keyword (:schema root))))))

(deftest analyse-throw-test
  (testing "original throw setup"
    (let [root (project-ast (analyze-form '(throw (UnsupportedOperationException. "oops, not done yet"))))]
      (is (= :throw (:op root)))
      (is (= sb/Bottom (:schema root)))
      (is (= '(throw (UnsupportedOperationException. "oops, not done yet"))
             (:form root))))))

(deftest analyse-try-test
  (testing "original try/catch setup"
    (let [root (project-ast (analyze-form '(try (+ 1 2)
                                                (catch UnsupportedOperationException e
                                                  (println "doesn't work")))))]
      (is (= :try (:op root)))
      (is (= :static-call (:op (child-projection root :body))))
      (is (= '(println "doesn't work")
             (:form (child-projection (first (child-projection root :catches)) :body))))))
  (testing "original try/catch/finally setup"
    (let [root (project-ast (analyze-form '(try (str 3)
                                                (+ 1 2)
                                                (catch UnsupportedOperationException e
                                                  (println "doesn't work")
                                                  (println "still doesn't"))
                                                (finally
                                                  (println "got something")
                                                  (+ 7 8)))))]
      (is (= :try (:op root)))
      (is (= :do (:op (child-projection root :body))))
      (is (= :do (:op (child-projection root :finally))))
      (is (find-projected-node root #(= '(println "still doesn't") (:form %)))))))

(deftest analyse-let-test
  (testing "original empty let setup"
    (let [root (project-ast (analyze-form '(let [] (+ 1 2))))]
      (is (= :static-call (:op root)))
      (is (= s/Any (:schema root)))))
  (testing "original simple let setup"
    (let [root (project-ast (analyze-form '(let [x 1] (+ 1 x))))]
      (is (= :let (:op root)))
      (is (= 1 (count (child-projection root :bindings))))))
  (testing "original nested let setup"
    (let [root (project-ast (analyze-form '(let [x (+ 1 2)
                                                 y (+ 3 x)]
                                             (+ 7 x)
                                             (+ x y))))]
      (is (= :let (:op root)))
      (is (= 2 (count (child-projection root :bindings))))
      (is (= :do (:op (child-projection root :body))))
      (is (= :static-call (:op (child-projection (child-projection root :body) :ret)))))))

(deftest analyse-if-test
  (testing "original literal if setup"
    (let [root (project-ast (analyze-form '(if (even? 2) true "hello")))]
      (is (= :if (:op root)))
      (is (= [:test :then :else] (mapv first (:children root))))))
  (testing "original symbol if setup"
    (let [root (project-ast (analyze-form '(if (pos? x) 1 -1)))]
      (is (= :if (:op root)))
      (is (= '(if (pos? x) 1 -1) (:form root))))))

(deftest analyse-fn-test
  (testing "original anonymous identity fn setup"
    (let [root (project-ast (analyze-form '(fn [x] x)))]
      (is (= :fn (:op root)))
      (is (= 1 (count (child-projection root :methods))))
      (is (= s/Any (:output root)))))
  (testing "original named identity fn setup"
    (let [root (project-ast (analyze-form sample-dict
                                          '(fn [x] x)
                                          {:name 'f}))]
      (is (= :fn (:op root)))
      (is (= [s/Int] (-> root :arglists (get 1) :schema (->> (mapv :schema)))))
      (is (= s/Int (:output root)))))
  (testing "original named binary fn setup"
    (let [root (project-ast (analyze-form sample-dict
                                          '(fn [y z] y)
                                          {:name 'f}))]
      (is (= :fn (:op root)))
      (is (= [s/Str s/Int] (-> root :arglists (get 2) :schema (->> (mapv :schema)))))
      (is (= s/Str (:output root)))))
  (testing "original multi-expression fn setup"
    (let [root (project-ast (analyze-form '(fn [x y]
                                             (str y x)
                                             (+ x y))))]
      (is (= :fn (:op root)))
      (is (= s/Any (:output root)))
      (is (= 1 (count (child-projection root :methods))))))
  (testing "original multi-arity fn setup"
    (let [root (project-ast (analyze-form '(fn* ([x] (+ x 1))
                                              ([x y] (+ x y)))))]
      (is (= :fn (:op root)))
      (is (= 2 (count (child-projection root :methods))))
      (is (= 2 (count (:arglists root)))))))

(deftest analyse-def-test
  (testing "original def setup"
    (let [root (project-ast (analyze-form '(def n 5)))]
      (is (= :def (:op root)))
      (is (= 'n (:name root)))
      (is (= (sb/variable s/Int) (:schema root)))))
  (testing "original defn setup"
    (let [root (project-ast (analyze-form '(defn f [x]
                                            (println "something")
                                            (+ 1 x))))]
      (is (= :def (:op root)))
      (is (= 'f (:name root)))
      (is (= :with-meta (:op (child-projection root :init))))
      (is (some #{'(defn f [x] (println "something") (+ 1 x))}
                (:raw-forms root))))))

(deftest analyse-do-test
  (let [root (project-ast (analyze-form '(do (str "hello") (+ 1 2))))]
    (is (= :do (:op root)))
    (is (= :static-call (:op (child-projection root :ret))))))

(deftest analyse-application-test
  (testing "original partially unknown application setup"
    (let [root (project-ast (analyze-form '(+ 1 x)))]
      (is (= :static-call (:op root)))
      (is (= [s/Int s/Any] (:actual-arglist root)))))
  (testing "original zero-arity application setup"
    (let [root (project-ast (analyze-form '(f)))]
      (is (= :invoke (:op root)))
      (is (= 0 (count (:actual-arglist root))))))
  (testing "original nested application setup"
    (let [root (project-ast (analyze-form '((f 1) 3 4)))]
      (is (= :invoke (:op root)))
      (is (= 2 (count (:actual-arglist root))))
      (is (= '(f 1) (:form (child-projection root :fn)))))))

(deftest analyse-coll-test
  (testing "original vector literal setup"
    (let [root (project-ast (analyze-form '[1 2 :a "hello"]))]
      (is (= :const (:op root)))
      (is (at/vector-type? (ab/schema->type (:schema root))))))
  (testing "original set literal setup"
    (let [root (project-ast (analyze-form '#{1 2 :a "hello"}))]
      (is (= :const (:op root)))
      (is (at/set-type? (ab/schema->type (:schema root))))))
  (testing "original list literal setup"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"is not a function"
         (analyze-form '(1 2 :a "hello")))))
  (testing "original map literal setup"
    (let [root (project-ast (analyze-form '{:a 1 :b 2 :c 3}))]
      (is (= :const (:op root)))
      (is (at/map-type? (ab/schema->type (:schema root))))))
  (testing "original nested vector setup"
    (let [root (project-ast (analyze-form '[1 2 [3 4 [5]]]))]
      (is (= :const (:op root)))
      (is (at/vector-type? (ab/schema->type (:schema root))))))
  (testing "original nested map setup"
    (let [root (project-ast (analyze-form '{:a 1 :b [:z "hello" #{1 2}]
                                           :c {:d 7 :e {:f 9}}}))]
      (is (= :const (:op root)))
      (is (at/map-type? (ab/schema->type (:schema root)))))))

(deftest attach-schema-info-value-test
  (let [root (project-ast (analyze-form {} '1))]
    (is (= s/Int (:schema root)))))

(deftest attach-schema-info-coll-test
  (testing "original empty list schema setup"
    (let [root (project-ast (analyze-form {} '()))]
      (is (= [s/Any] (:schema root)))))
  (testing "original simple vector schema setup"
    (let [root (project-ast (analyze-form {} '[1 2]))]
      (is (= [s/Int s/Int] (:schema root)))))
  (testing "original nested vector/map schema setup"
    (let [root (project-ast (analyze-form {} '[1 {:a 2 :b {:c #{3 4}}} 5]))]
      (is (vector? (:schema root)))
      (is (= 3 (count (:schema root)))))))

(deftest attach-schema-info-application-test
  (testing "original generic application schema setup"
    (let [root (project-ast (analyze-form {} '(+ 1 2)))]
      (is (= [s/Int s/Int] (:actual-arglist root)))
      (is (= s/Any (:schema root)))))
  (testing "original known application schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(skeptic.test-examples/int-add 1 2)))]
      (is (= [s/Int s/Int] (:actual-arglist root)))
      (is (= [s/Int s/Int] (:expected-arglist root)))
      (is (= s/Int (:schema root))))))

(deftest attach-schema-info-let-test
  (testing "original empty let schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(let [] (skeptic.test-examples/int-add 1 2))))]
      (is (= s/Int (:schema root)))))
  (testing "original bound let schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(let [x (skeptic.test-examples/int-add 1 2)]
                                             (skeptic.test-examples/int-add x 2))))]
      (is (= :let (:op root)))
      (is (= s/Int (:schema root)))))
  (testing "original or/let schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(let [y nil
                                                 x (or y 1)]
                                             (skeptic.test-examples/int-add x 2))))]
      (is (= (T s/Int) (:type root)))
      (is (find-projected-node root #(and (= :if (:op %))
                                          (= (T (sb/join s/Any s/Int)) (:type %))))))))

(deftest attach-schema-info-if-test
  (testing "original literal if schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(if (even? 2) true "hello")))]
      (is (= (T (sb/join s/Bool s/Str)) (:type root)))))
  (testing "original symbol if schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(if (pos? x) 1 -1)))]
      (is (= (T s/Int) (:type root)))))
  (testing "original maybe-refinement if setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(let [x nil] (if x x 1))))]
      (is (= (T (sb/join s/Any s/Int)) (:type root)))))
  (testing "original or macro schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(or nil 1)))]
      (is (= :let (:op root)))
      (is (= (T (sb/join s/Any s/Int)) (:type root))))))

(deftest attach-schema-info-fn-test
  (let [root (project-ast (analyze-form test-examples/sample-dict
                                        '(fn [x] (skeptic.test-examples/int-add 1 2))))]
    (is (= :fn (:op root)))
    (is (= (T s/Int) (:output-type root)))
    (is (= [(T s/Any)] (arglist-types root 1)))))

(deftest attach-schema-info-def-test
  (testing "original def schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(def n 5)))]
      (is (= (sb/variable s/Int) (:schema root)))))
  (testing "original defn schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(defn f [x y]
                                             (println "something")
                                             (skeptic.test-examples/int-add x y))))]
      (is (sb/variable? (:schema root)))
      (is (= s/Int (-> root :schema :schema :output-schema)))))
  (testing "original typed defn schema setup"
    (let [root (project-ast (analyze-form sample-dict
                                          '(defn f [y z]
                                             (println y)
                                             z)))]
      (is (sb/variable? (:schema root)))
      (is (= s/Int (-> root :schema :schema :output-schema))))))

(deftest attach-schema-info-do-test
  (let [root (project-ast (analyze-form test-examples/sample-dict
                                        '(do
                                           (println "something")
                                           (skeptic.test-examples/int-add x y))))]
    (is (= :do (:op root)))
    (is (= s/Int (:schema root)))))

(deftest attach-schema-info-try-throw-test
  (testing "original try/throw schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(try
                                             (skeptic.test-examples/int-add 1 2)
                                             (catch UnsupportedOperationException e
                                               (throw e)))))]
      (is (= s/Int (:schema root)))
      (is (= sb/Bottom
             (:schema (find-projected-node root #(= '(throw e) (:form %))))))))
  (testing "original try/catch/finally schema setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
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
      (is (= :do (:op (child-projection root :finally))))
      (is (find-projected-node root #(= '(println "oops") (:form %)))))))

(deftest attach-schema-info-misc-tests
  (testing "original nested bad fn setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(defn sample-bad-fn
                                             [x]
                                             (skeptic.test-examples/int-add
                                              1
                                              (skeptic.test-examples/int-add nil x)))))]
      (is (sb/variable? (:schema root)))
      (is (find-projected-node root #(= '(skeptic.test-examples/int-add nil x) (:form %))))
      (is (find-projected-node root #(= '(skeptic.test-examples/int-add 1 (skeptic.test-examples/int-add nil x))
                                        (:form %))))))
  (testing "original local fn invocation setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(let [f (fn [x] nil)]
                                             (skeptic.test-examples/int-add 1 (f x)))))]
      (is (= s/Int (:schema root)))
      (is (= (s/maybe s/Any)
             (:schema (find-projected-node root #(= '(f x) (:form %))))))))
  (testing "original fn-once setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(defn sample-fn-once
                                             [x]
                                             ((^{:once true} fn* [y] (int-add y nil))
                                              x))))]
      (is (sb/variable? (:schema root)))
      (is (find-projected-node root #(= :with-meta (:op %))))))
  (testing "original sample-path-fn setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(defn sample-path-fn
                                             [x]
                                             (let [f (+ 1 x)]
                                               (f x)))))]
      (is (sb/variable? (:schema root)))
      (is (find-projected-node root #(= '(f x) (:form %))))))
  (testing "original doto component setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(doto (make-component {:a 1 :b 2})
                                             (start {:opt1 true}))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))
      (is (find-projected-node root #(= '(start G {:opt1 true}) (:form %))))
      (is (find-projected-node root #(and (= :const (:op %))
                                          (at/map-type? (ab/schema->type (:schema %)))
                                          (= {:a 1 :b 2} (:form %))))))))

(deftest analyse-problematic-let-test
  (testing "original single-call doto setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(doto (set-cache-value 1))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))
      (is (find-projected-node root #(= '(set-cache-value 1) (:form %))))))
  (testing "original cond-> equality setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(cond-> :invalid
                                             true
                                             (= :valid))))]
      (is (= :let (:op root)))
      (is (find-projected-node root #(= :if (:op %))))
      (is (= (sb/join s/Any s/Keyword) (:schema root)))))
  (testing "original thread-first doto setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(-> cache
                                               (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (= s/Any (:schema root)))))
  (testing "original cond-> doto setup"
    (let [root (project-ast (analyze-form test-examples/sample-dict
                                          '(cond-> :invalid
                                             true
                                             (doto set-cache-value))))]
      (is (= :let (:op root)))
      (is (find-projected-node root #(= :if (:op %))))
      (is (= s/Keyword (:schema root))))))

(deftest canonicalized-schema-representation-test
  (let [raw-symbol-entry {:schema (s/make-fn-schema clojure.lang.Symbol
                                                    [[(s/one java.lang.String 'arg)]])
                          :output clojure.lang.Symbol
                          :arglists {1 {:arglist ['arg]
                                        :count 1
                                        :schema [{:schema java.lang.String
                                                  :optional? false
                                                  :name 'arg}]}}}
        raw-keyword-entry {:schema (s/make-fn-schema clojure.lang.Keyword
                                                     [[(s/one s/Any 'arg)]])
                           :output clojure.lang.Keyword
                           :arglists {1 {:arglist ['arg]
                                         :count 1
                                         :schema [{:schema s/Any
                                                   :optional? false
                                                   :name 'arg}]}}}
        raw-int-entry {:schema (s/make-fn-schema java.lang.Integer
                                                 [[(s/one s/Any 'arg)]])
                       :output java.lang.Integer
                       :arglists {1 {:arglist ['arg]
                                     :count 1
                                     :schema [{:schema s/Any
                                               :optional? false
                                               :name 'arg}]}}}
        symbol-call (project-ast (analyze-form {}
                                               '(f "x")
                                               {:ns 'skeptic.analysis-test
                                                :locals {'f raw-symbol-entry}}))
        keyword-call (project-ast (analyze-form {}
                                                '(f :x)
                                                {:ns 'skeptic.analysis-test
                                                 :locals {'f raw-keyword-entry}}))
        int-call (project-ast (analyze-form {}
                                            '(f :x)
                                            {:ns 'skeptic.analysis-test
                                             :locals {'f raw-int-entry}}))
        quoted-symbol (project-ast (analyze-form '(quote foo)))]
    (is (= s/Symbol (:schema symbol-call)))
    (is (= [s/Str] (:expected-arglist symbol-call)))
    (is (= s/Keyword (:schema keyword-call)))
    (is (= s/Int (:schema int-call)))
    (is (= s/Symbol (:schema quoted-symbol)))))

(deftest static-call-analysis-test
  (testing "get returns field schemas from typed maps"
    (let [required-get (project-ast (analyze-form static-call-dict
                                                  '(get user :name)
                                                  {:ns 'skeptic.analysis-test
                                                   :locals {'user skeptic.static-call-examples/UserDesc}}))
          optional-get (project-ast (analyze-form static-call-dict
                                                  '(get user :nickname)
                                                  {:ns 'skeptic.analysis-test
                                                   :locals {'user skeptic.static-call-examples/UserDesc}}))
          defaulted-get (project-ast (analyze-form static-call-dict
                                                   '(get counts :count "zero")
                                                   {:ns 'skeptic.analysis-test
                                                    :locals {'counts skeptic.static-call-examples/MaybeCount}}))]
      (is (= s/Str (:schema required-get)))
      (is (= (s/maybe s/Str) (:schema optional-get)))
      (is (as/schema-equivalent? (sb/join s/Int s/Str)
                                 (:schema defaulted-get)))))

  (testing "merge returns merged map schemas"
    (let [merged (project-ast (analyze-form static-call-dict
                                            '(merge left right)
                                            {:ns 'skeptic.analysis-test
                                             :locals {'left skeptic.static-call-examples/LeftFields
                                                      'right skeptic.static-call-examples/RightFields}}))]
      (is (= {:a s/Int :b s/Int}
             (:schema merged)))))

  (testing "rebuilt maps stay in semantic map format"
    (let [root (project-ast (analyze-form static-call-dict
                                          '{:name (get user :name)
                                            :nickname (get user :nickname)}
                                          {:ns 'skeptic.analysis-test
                                           :locals {'user skeptic.static-call-examples/UserDesc}}))]
      (is (= {:name s/Str
              :nickname (s/maybe s/Str)}
             (:schema root))))))

(deftest restored-resolution-contract-test
  (testing "unannotated helper lookup from ns-schemas alone stays plain Any"
    (let [dict (schematize/ns-schemas {} 'skeptic.test-examples)
          form (->> 'skeptic.test-examples/unannotated-local-helper-g
                    (schematize/get-fn-code {})
                    read-string)
          ast (sut/attach-schema-info-loop dict form {:ns 'skeptic.test-examples})
          call-node (node-by-form ast '(unannotated-local-helper-f))]
      (is (= (T s/Any) (:type call-node)))
      (is (not (at/union-type? (:type call-node))))))

  (testing "declared helper chains use declared outputs exactly"
    (let [dict (schematize/ns-schemas {} 'skeptic.test-examples)
          {:keys [resolved resolved-defs]} (checking/analyze-source-exprs dict
                                                                         'skeptic.test-examples
                                                                         test-examples-file
                                                                         (source-exprs-in 'skeptic.test-examples test-examples-file))
          failure-ast (ast-by-name resolved 'flat-multi-step-failure)
          call-node (node-by-form failure-ast '(flat-multi-step-takes-str (flat-multi-step-g)))]
      (is (= (T s/Int) (get-in resolved-defs ['skeptic.test-examples/flat-multi-step-f :output-type])))
      (is (= (T s/Int) (get-in resolved-defs ['skeptic.test-examples/flat-multi-step-g :output-type])))
      (is (= [(T s/Int)] (:actual-argtypes call-node)))
      (is (not (at/union-type? (get-in resolved-defs ['skeptic.test-examples/flat-multi-step-g :output-type]))))))

  (testing "branch joins stay branch-local and nil-bearing joins canonicalize to maybe"
    (let [test-dict (schematize/ns-schemas {} 'skeptic.test-examples)
          example-dict (schematize/ns-schemas {} 'skeptic.examples)
          test-res (checking/analyze-source-exprs test-dict
                                                 'skeptic.test-examples
                                                 test-examples-file
                                                 (source-exprs-in 'skeptic.test-examples test-examples-file))
          example-res (checking/analyze-source-exprs example-dict
                                                    'skeptic.examples
                                                    examples-file
                                                    (source-exprs-in 'skeptic.examples examples-file))]
      (is (= (T (sb/join s/Int s/Str))
             (get-in test-res [:resolved-defs 'skeptic.test-examples/sample-if-mixed-fn :output-type])))
      (is (= (T s/Int)
             (get-in test-res [:resolved-defs 'skeptic.test-examples/flat-multi-step-g :output-type])))
      (is (= (T (s/maybe s/Int))
             (get-in example-res [:resolved-defs 'skeptic.examples/flat-maybe-multi-step-f :output-type])))
      (is (= (T {:value (s/maybe s/Int)})
             (get-in example-res [:resolved-defs 'skeptic.examples/nested-maybe-multi-step-f :output-type])))))

  (testing "resolved static get feeds final reduced schemas into parent calls"
    (let [dict (schematize/ns-schemas {} 'skeptic.static-call-examples)
          {:keys [resolved]} (checking/analyze-source-exprs dict
                                                            'skeptic.static-call-examples
                                                            static-call-examples-file
                                                            (source-exprs-in 'skeptic.static-call-examples static-call-examples-file))
          failure-ast (ast-by-name resolved 'nested-multi-step-failure)
          call-node (node-by-form failure-ast '(nested-multi-step-takes-str (get (nested-multi-step-g) :value)))]
      (is (= [(T s/Int)] (:actual-argtypes call-node))))))

(deftest declaration-index-contract-test
  (let [dict (schematize/ns-schemas {} 'skeptic.test-examples)
        forward-entry (sut/normalize-entry (get dict 'skeptic.test-examples/forward-declared-target))
        recursive-entry (sut/normalize-entry (get dict 'skeptic.test-examples/self-recursive-identity))]
    (is (= [(T s/Any)]
           (-> forward-entry :arglists (get 1) :types (->> (mapv :type)))))
    (is (= (T s/Any) (:output-type forward-entry)))
    (is (= [(T s/Any)]
           (-> recursive-entry :arglists (get 1) :types (->> (mapv :type)))))
    (is (= (T s/Any) (:output-type recursive-entry)))))
