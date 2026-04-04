(ns skeptic.analysis.origin-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.examples]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis-test :as atst]
            [skeptic.checking.pipeline :as checking]
            [skeptic.schematize :as schematize]
            [skeptic.test-examples :as test-examples]))

(deftest origin-constructors-unit-test
  (testing "opaque origin tags normalized type"
    (is (= :opaque (:kind (ao/opaque-origin (atst/T s/Int))))))
  (testing "root origin carries sym and type"
    (let [o (ao/root-origin 'x (atst/T s/Str))]
      (is (= :root (:kind o)))
      (is (= 'x (:sym o)))
      (is (= (atst/T s/Str) (:type o)))))
  (testing "effective-entry returns typed data only"
    (let [entry (ao/effective-entry 'x (atst/T s/Int) [])]
      (is (= (atst/T s/Int) (:type entry)))
      (is (not (contains? entry :schema))))))

(deftest schema-binding-and-refinement-test
  (testing "let-driven flow through or expands to refinable branch"
    (let [or-let (atst/project-ast (atst/analyze-form '(let [y nil
                                                             x (or y 1)]
                                                         (skeptic.test-examples/int-add x 2))))]
      (is (= (atst/T s/Int) (:type or-let)))
      (is (atst/find-projected-node or-let #(and (= :if (:op %))
                                                  (= (atst/T (sb/join s/Any s/Int)) (:type %)))))))
  (testing "if refinement and joins"
    (let [literal-if (atst/project-ast (atst/analyze-form '(if (even? 2) true "hello")))
          local-if (atst/project-ast (atst/analyze-form '(if (pos? x) 1 -1)
                                                       (atst/locals 'x)))
          maybe-if (atst/project-ast (atst/analyze-form '(let [x nil] (if x x 1))))
          or-form (atst/project-ast (atst/analyze-form '(or nil 1)))]
      (is (= (atst/T (sb/join s/Bool s/Str)) (:type literal-if)))
      (is (= (atst/T s/Int) (:type local-if)))
      (is (= (atst/T (sb/join s/Any s/Int)) (:type maybe-if)))
      (is (= :let (:op or-form)))
      (is (= (atst/T (sb/join s/Any s/Int)) (:type or-form))))))

(deftest attach-schema-branch-refinement-test
  (testing "or/let schema setup exposes branch join on inner if"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                                    '(let [y nil
                                                           x (or y 1)]
                                                       (skeptic.test-examples/int-add x 2))))]
      (is (= (atst/T s/Int) (:type root)))
      (is (atst/find-projected-node root #(and (= :if (:op %))
                                                (= (atst/T (sb/join s/Any s/Int)) (:type %)))))))
  (testing "literal if schema join"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                                    '(if (even? 2) true "hello")))]
      (is (= (atst/T (sb/join s/Bool s/Str)) (:type root)))))
  (testing "symbol test if keeps output type when branches agree"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                                    '(if (pos? x) 1 -1)))]
      (is (= (atst/T s/Int) (:type root)))))
  (testing "maybe-refinement if joins nilable branch with default"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                                    '(let [x nil] (if x x 1))))]
      (is (= (atst/T (sb/join s/Any s/Int)) (:type root)))))
  (testing "or macro schema matches expanded branch join"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                                    '(or nil 1)))]
      (is (= :let (:op root)))
      (is (= (atst/T (sb/join s/Any s/Int)) (:type root))))))

(deftest branch-resolution-joins-test
  (testing "branch joins stay branch-local and nil-bearing joins canonicalize to maybe"
    (let [test-dict (schematize/typed-ns-schemas {} 'skeptic.test-examples)
          example-dict (schematize/typed-ns-schemas {} 'skeptic.examples)
          test-res (checking/analyze-source-exprs test-dict
                                                  'skeptic.test-examples
                                                  atst/test-examples-file
                                                  (atst/source-exprs-in 'skeptic.test-examples atst/test-examples-file))
          example-res (checking/analyze-source-exprs example-dict
                                                     'skeptic.examples
                                                     atst/examples-file
                                                     (atst/source-exprs-in 'skeptic.examples atst/examples-file))]
      (is (= (atst/T (sb/join s/Int s/Str))
             (get-in test-res [:resolved-defs 'skeptic.test-examples/sample-if-mixed-fn :output-type])))
      (is (= (atst/T s/Int)
             (get-in test-res [:resolved-defs 'skeptic.test-examples/flat-multi-step-g :output-type])))
      (is (= (atst/T (s/maybe s/Int))
             (get-in example-res [:resolved-defs 'skeptic.examples/flat-maybe-multi-step-f :output-type])))
      (is (= (atst/T {:value (s/maybe s/Int)})
             (get-in example-res [:resolved-defs 'skeptic.examples/nested-maybe-multi-step-f :output-type]))))))
