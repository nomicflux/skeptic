(ns skeptic.analysis.annotate.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.analyzer.ast :as ana.ast]
            [schema.core :as s]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.cast :as cast]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]))

(deftest case-conditional-narrowing-through-destructured-keyword-local-test
  (let [conditional-route-map
        (s/conditional
          #(= :a (:route %)) {:route (s/eq :a) :a s/Int}
          #(= :b (:route %)) {:route (s/eq :b) :c s/Bool})
        root
        (atst/project-ast
          (atst/analyze-form
            '((fn [x]
                (let [route (get x :route)]
                  (case route
                    :a (skeptic.test-examples/takes-has-a x)
                    :b x)))
              input)
            (atst/local-types {'input {:type (atst/T conditional-route-map)}})))
        takes-a-call
        (atst/find-projected-node root
                                  #(= '(skeptic.test-examples/takes-has-a x) (:form %)))]
    (is (some? takes-a-call))
    (is (at/type-equal? (first (:actual-argtypes takes-a-call))
                        (first (:expected-argtypes takes-a-call))))
    (is (:ok? (cast/check-cast (first (:actual-argtypes takes-a-call))
                               (first (:expected-argtypes takes-a-call)))))))

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
                                   {:ns 'skeptic.analysis-test})
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
