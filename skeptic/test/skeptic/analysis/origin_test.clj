(ns skeptic.analysis.origin-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.examples]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis-test :as atst]
            [skeptic.checking.pipeline :as checking]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.test-examples :as test-examples])
  (:import [clojure.lang Numbers]))

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

(deftest typed-binding-and-refinement-test
  (testing "let-driven flow through or expands to refinable branch"
    (let [or-let (atst/analyze-form '(let [y nil
                                           x (or y 1)]
                                       (skeptic.test-examples/int-add x 2)))]
      (is (= (atst/T s/Int) (aapi/node-type or-let)))
      (is (aapi/find-node or-let #(and (= :if (aapi/node-op %))
                                       (= (atst/T s/Int) (aapi/node-type %)))))))
  (testing "if refinement and joins"
    (let [literal-if (atst/analyze-form '(if (even? 2) true "hello"))
          local-if (atst/analyze-form '(if (pos? x) 1 -1)
                                      (atst/locals 'x))
          maybe-if (atst/analyze-form '(let [x nil] (if x x 1)))
          or-form (atst/analyze-form '(or nil 1))]
      (is (= (atst/T (sb/join s/Bool s/Str)) (aapi/node-type literal-if)))
      (is (= (atst/T s/Int) (aapi/node-type local-if)))
      (is (= (atst/T s/Int) (aapi/node-type maybe-if)))
      (is (= :let (aapi/node-op or-form)))
      (is (= (atst/T s/Int) (aapi/node-type or-form))))))

(deftest attach-type-branch-refinement-test
  (testing "or/let typed setup exposes branch join on inner if"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(let [y nil
                                         x (or y 1)]
                                     (skeptic.test-examples/int-add x 2)))]
      (is (= (atst/T s/Int) (aapi/node-type root)))
      (is (aapi/find-node root #(and (= :if (aapi/node-op %))
                                     (= (atst/T s/Int) (aapi/node-type %)))))))
  (testing "literal if join"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(if (even? 2) true "hello"))]
      (is (= (atst/T (sb/join s/Bool s/Str)) (aapi/node-type root)))))
  (testing "symbol test if keeps output type when branches agree"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(if (pos? x) 1 -1))]
      (is (= (atst/T s/Int) (aapi/node-type root)))))
  (testing "maybe-refinement if joins nilable branch with default"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(let [x nil] (if x x 1)))]
      (is (= (atst/T s/Int) (aapi/node-type root)))))
  (testing "or macro matches expanded branch join"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(or nil 1))]
      (is (= :let (aapi/node-op root)))
      (is (= (atst/T s/Int) (aapi/node-type root))))))

(deftest branch-resolution-joins-test
  (testing "branch joins stay branch-local and nil-bearing joins canonicalize to maybe"
    (let [test-dict (typed-decls/typed-ns-entries {} 'skeptic.test-examples)
          example-dict (typed-decls/typed-ns-entries {} 'skeptic.examples)
          test-res (checking/analyze-source-exprs test-dict
                                                  'skeptic.test-examples
                                                  atst/test-examples-file
                                                  (atst/source-exprs-in 'skeptic.test-examples atst/test-examples-file))
          example-res (checking/analyze-source-exprs example-dict
                                                     'skeptic.examples
                                                     atst/examples-file
                                                     (atst/source-exprs-in 'skeptic.examples atst/examples-file))]
      (is (= (atst/T (sb/join s/Int s/Str))
             (aapi/resolved-def-output-type (:resolved-defs test-res)
                                            'skeptic.test-examples/sample-if-mixed-fn)))
      (is (= (atst/T s/Int)
             (aapi/resolved-def-output-type (:resolved-defs test-res)
                                            'skeptic.test-examples/flat-multi-step-g)))
      (is (= (atst/T (s/maybe s/Int))
             (aapi/resolved-def-output-type (:resolved-defs example-res)
                                            'skeptic.examples/flat-maybe-multi-step-f)))
      (is (= (atst/T {:value (s/maybe s/Int)})
             (aapi/resolved-def-output-type (:resolved-defs example-res)
                                            'skeptic.examples/nested-maybe-multi-step-f))))))

(deftest and-chain-assumptions-two-some-test
  (testing "expanded and collects each some? conjunct from macro shape"
    (let [root (atst/analyze-form atst/analysis-dict '(and (some? x) (some? y))
                                  {:locals {'x (atst/T (s/maybe s/Int))
                                            'y (atst/T (s/maybe s/Str))}})
          parts (ao/and-chain-assumptions root)]
      (is (= 2 (count parts)))
      (is (every? #(and (= :type-predicate (:kind %))
                        (= :some? (:pred %)))
                  parts)))))

(deftest let-shadow-nil-check-root-origin-some-to-lambda-shape-test
  (testing "shadowed let + param alias: nil? on shadow name still gets :root so outer else refines unary -"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(fn [input]
                                     (let [x input
                                           x (if (nil? x) nil (skeptic.test-examples/non-null-transform x))]
                                       (if (nil? x) nil (#(- %) x))))
                                  {:locals {'input (atst/T (s/maybe s/Num))}})
          minus (aapi/find-node root
                                #(and (= :static-call (aapi/node-op %))
                                      (= Numbers (aapi/node-class %))
                                      (= 'minus (aapi/node-method %))))]
      (is (some? minus) "expected unary - lowered to Numbers/minus")
      (is (= atst/num-ground (first (aapi/call-actual-argtypes minus)))))))
