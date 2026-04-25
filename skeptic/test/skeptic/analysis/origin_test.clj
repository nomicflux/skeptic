(ns skeptic.analysis.origin-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.examples]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]
            [skeptic.checking.pipeline :as checking]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-helpers :refer [tp]]
            [skeptic.typed-decls :as typed-decls])
  (:import [clojure.lang Numbers]))

(deftest origin-constructors-unit-test
  (testing "opaque origin tags normalized type"
    (is (= :opaque (:kind (ao/opaque-origin (atst/T s/Int))))))
  (testing "root origin carries sym and type"
    (let [o (ao/root-origin 'x (atst/T s/Str))]
      (is (= :root (:kind o)))
      (is (= 'x (:sym o)))
      (is (at/type=? (atst/T s/Str) (:type o)))))
  (testing "effective-type returns refined type"
    (is (at/type=? (atst/T s/Int) (ao/effective-type tp 'x (atst/T s/Int) [])))))

(deftest typed-binding-and-refinement-test
  (testing "let-driven flow through or expands to refinable branch"
    (let [or-let (atst/analyze-form '(let [y nil
                                           x (or y 1)]
                                       (skeptic.test-examples.basics/int-add x 2)))]
      (is (at/type=? (atst/T s/Int) (aapi/node-type or-let)))
      (is (aapi/find-node or-let #(and (= :if (aapi/node-op %))
                                       (at/type=? (atst/T s/Int) (aapi/node-type %)))))))
  (testing "if refinement and joins"
    (let [literal-if (atst/analyze-form '(if (even? 2) true "hello"))
          local-if (atst/analyze-form '(if (pos? x) 1 -1)
                                      (atst/locals 'x))
          maybe-if (atst/analyze-form '(let [x nil] (if x x 1)))
          or-form (atst/analyze-form '(or nil 1))]
      (is (at/type=? (atst/T (sb/join s/Bool s/Str)) (aapi/node-type literal-if)))
      (is (at/type=? (atst/T s/Int) (aapi/node-type local-if)))
      (is (at/type=? (atst/T s/Int) (aapi/node-type maybe-if)))
      (is (= :let (aapi/node-op or-form)))
      (is (at/type=? (atst/T s/Int) (aapi/node-type or-form))))))

(deftest attach-type-branch-refinement-test
  (testing "or/let typed setup exposes branch join on inner if"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(let [y nil
                                         x (or y 1)]
                                     (skeptic.test-examples.basics/int-add x 2)))]
      (is (at/type=? (atst/T s/Int) (aapi/node-type root)))
      (is (aapi/find-node root #(and (= :if (aapi/node-op %))
                                     (at/type=? (atst/T s/Int) (aapi/node-type %)))))))
  (testing "literal if join"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(if (even? 2) true "hello"))]
      (is (at/type=? (atst/T (sb/join s/Bool s/Str)) (aapi/node-type root)))))
  (testing "symbol test if keeps output type when branches agree"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(if (pos? x) 1 -1))]
      (is (at/type=? (atst/T s/Int) (aapi/node-type root)))))
  (testing "maybe-refinement if joins nilable branch with default"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(let [x nil] (if x x 1)))]
      (is (at/type=? (atst/T s/Int) (aapi/node-type root)))))
  (testing "or macro matches expanded branch join"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(or nil 1))]
      (is (= :let (aapi/node-op root)))
      (is (at/type=? (atst/T s/Int) (aapi/node-type root))))))

(deftest branch-resolution-joins-test
  (testing "branch joins stay branch-local and nil-bearing joins canonicalize to maybe"
    (let [test-dict (catalog/typed-test-example-entries)
          example-dict (:dict (typed-decls/typed-ns-results {} 'skeptic.examples))
          control-flow-res (checking/analyze-source-exprs test-dict
                                                          'skeptic.test-examples.control-flow
                                                          (atst/fixture-file-for-ns 'skeptic.test-examples.control-flow)
                                                          (atst/source-exprs-in 'skeptic.test-examples.control-flow
                                                                                (atst/fixture-file-for-ns 'skeptic.test-examples.control-flow)))
          resolution-res (checking/analyze-source-exprs test-dict
                                                        'skeptic.test-examples.resolution
                                                        (atst/fixture-file-for-ns 'skeptic.test-examples.resolution)
                                                        (atst/source-exprs-in 'skeptic.test-examples.resolution
                                                                              (atst/fixture-file-for-ns 'skeptic.test-examples.resolution)))
          example-res (checking/analyze-source-exprs example-dict
                                                     'skeptic.examples
                                                     atst/examples-file
                                                     (atst/source-exprs-in 'skeptic.examples atst/examples-file))]
      (is (at/type=? (atst/T (sb/join s/Int s/Str))
             (aapi/resolved-def-output-type (:resolved-defs control-flow-res)
                                            'skeptic.test-examples.control-flow/sample-if-mixed-fn)))
      (is (at/type=? (atst/T s/Int)
             (aapi/resolved-def-output-type (:resolved-defs resolution-res)
                                            'skeptic.test-examples.resolution/flat-multi-step-g)))
      (is (at/type=? (atst/T (s/maybe s/Int))
             (aapi/resolved-def-output-type (:resolved-defs example-res)
                                            'skeptic.examples/flat-maybe-multi-step-f)))
      (is (at/type=? (atst/T {:value (s/maybe s/Int)})
             (aapi/resolved-def-output-type (:resolved-defs example-res)
                                            'skeptic.examples/nested-maybe-multi-step-f))))))

(deftest region-conjuncts-and-shape-two-some-test
  (testing "let+if and-shape collects each some? conjunct on the truthy side and emits a disjunction of negations on the falsy side"
    (let [root (atst/analyze-form atst/analysis-dict '(and (some? x) (some? y))
                                  {:locals {'x (atst/T (s/maybe s/Int))
                                            'y (atst/T (s/maybe s/Str))}})
          {:keys [then-conjuncts else-conjuncts]} (ao/region-conjuncts tp root nil)]
      (is (= 2 (count then-conjuncts)))
      (is (every? #(and (= :type-predicate (:kind %))
                        (= :some? (:pred %))
                        (true? (:polarity %)))
                  then-conjuncts))
      (is (= 1 (count else-conjuncts)))
      (let [d (first else-conjuncts)]
        (is (= :disjunction (:kind d)))
        (is (= 2 (count (:parts d))))
        (is (every? #(and (= :type-predicate (:kind %))
                          (= :some? (:pred %))
                          (false? (:polarity %)))
                    (:parts d)))))))

(deftest equality-test-assumptions
  (testing "local equals literal"
    (let [root (atst/analyze-form atst/analysis-dict '(= x :a)
                                  {:locals {'x (atst/T (s/enum :a :b))}})
          assumption (ao/test->assumption tp root)]
      (is (= :value-equality (:kind assumption)))
      (is (= 'x (get-in assumption [:root :sym])))
      (is (= [:a] (:values assumption)))))
  (testing "literal equals local"
    (let [root (atst/analyze-form atst/analysis-dict '(= :a x)
                                  {:locals {'x (atst/T (s/enum :a :b))}})
          assumption (ao/test->assumption tp root)]
      (is (= :value-equality (:kind assumption)))
      (is (= 'x (get-in assumption [:root :sym])))
      (is (= [:a] (:values assumption))))))

(deftest let-shadow-nil-check-root-origin-some-to-lambda-shape-test
  (testing "shadowed let + param alias: nil? on shadow name still gets :root so outer else refines unary -"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(fn [input]
                                     (let [x input
                                           x (if (nil? x) nil (skeptic.test-examples.nullability/non-null-transform x))]
                                       (if (nil? x) nil (#(- %) x))))
                                  {:locals {'input (atst/T (s/maybe s/Num))}})
          minus (aapi/find-node root
                                #(and (= :static-call (aapi/node-op %))
                                      (= Numbers (aapi/node-class %))
                                      (= 'minus (aapi/node-method %))))]
      (is (some? minus) "expected unary - lowered to Numbers/minus")
      (is (at/type=? atst/numeric-dyn (first (aapi/call-actual-argtypes minus)))))))

(deftest negated-assumptions-and-narrowing-alias-roots-test
  (testing "not around nil? inverts the branch assumption"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(if (not (nil? x)) x "fallback")
                                  {:locals {'x (atst/T (s/maybe s/Str))}})
          if-node (aapi/find-node root #(= :if (aapi/node-op %)))
          then-x (aapi/find-node (aapi/then-node if-node)
                                 #(and (= :local (aapi/node-op %))
                                       (= 'x (aapi/node-form %))))]
      (is (at/type=? (atst/T s/Str) (aapi/node-type if-node)))
      (is (at/type=? (atst/T s/Str) (aapi/node-type then-x)))))

  (testing "narrowing-preserving aliases get their own root for later refinement"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(let [raw input
                                         p (when (some? raw) raw)]
                                     (if (some? p) p nil))
                                  {:locals {'input (atst/T (s/maybe s/Str))}})
          then-p (aapi/find-node root
                                 #(and (= :local (aapi/node-op %))
                                       (= 'p (aapi/node-form %))
                                       (= (atst/T s/Str) (aapi/node-type %))))]
      (is (at/type=? (atst/T s/Str) (aapi/node-type then-p)))
      (is (= 'p (:sym (ao/local-root-origin tp then-p)))))))

(deftest guarded-keys-maybe-s-caller-origin-test
  (let [dict (catalog/typed-test-example-entries)
        {:keys [resolved]} (checking/analyze-source-exprs dict
                                                          'skeptic.test-examples.nullability
                                                          (atst/fixture-file-for-ns 'skeptic.test-examples.nullability)
                                                          (atst/source-exprs-in 'skeptic.test-examples.nullability
                                                                                (atst/fixture-file-for-ns 'skeptic.test-examples.nullability)))
        ast (atst/ast-by-name resolved 'guarded-keys-caller)
        guarded-if (aapi/find-node ast #(and (= :if (aapi/node-op %))
                                             (= 'pair (aapi/node-form (aapi/node-test %)))))
        pair-assumption (aapi/branch-test-assumption guarded-if)
        lookup-nodes (filter #(and (= :static-call (aapi/node-op %))
                                   (= clojure.lang.RT (aapi/node-class %))
                                   (= 'get (aapi/node-method %)))
                             (aapi/annotated-nodes ast))]
    (is (= 'pair (get-in pair-assumption [:root :sym])))
    (is (= :truthy-local (:kind pair-assumption)))
    (is (= 2 (count lookup-nodes)))
    (doseq [lookup lookup-nodes]
      (is (= :map-key-lookup (:kind (aapi/node-origin lookup))))
      (is (at/type=? (atst/T s/Str)
                     (ao/origin-type (aapi/node-origin lookup) [pair-assumption]))))))

(defn- bp [expr polarity] {:kind :boolean-proposition :expr expr :polarity polarity})
(defn- disj* [& parts] {:kind :disjunction :parts (vec parts)})
(defn- conj* [& parts] {:kind :conjunction :parts (vec parts)})

(deftest assumption-truth-disjunction-arm
  (let [p     (bp '(pos? x) true)
        q     (bp '(pos? y) true)
        not-p (assoc p :polarity false)
        not-q (assoc q :polarity false)
        d     (disj* p q)]
    (is (= :true (ao/assumption-truth d [p])))
    (is (= :false (ao/assumption-truth d [not-p not-q])))
    (is (= :unknown (ao/assumption-truth d [])))))

(deftest region-conjuncts-and-shape-emits-disjunction
  (let [root (atst/analyze-form atst/analysis-dict '(and (pos? x) (pos? y))
                                {:locals {'x (atst/T s/Int) 'y (atst/T s/Int)}})
        {:keys [then-conjuncts else-conjuncts]} (ao/region-conjuncts tp root nil)]
    (is (= 2 (count then-conjuncts)))
    (is (every? #(and (= :boolean-proposition (:kind %))
                      (true? (:polarity %)))
                then-conjuncts))
    (is (= 1 (count else-conjuncts)))
    (let [d (first else-conjuncts)]
      (is (= :disjunction (:kind d)))
      (is (= 2 (count (:parts d))))
      (is (every? #(and (= :boolean-proposition (:kind %))
                        (false? (:polarity %)))
                  (:parts d))))))

(deftest assumption-truth-truth-table-fallback
  (let [p     (bp '(pos? x) true)
        q     (bp '(pos? y) true)
        not-p (assoc p :polarity false)
        not-q (assoc q :polarity false)
        d1    (disj* not-p not-q)
        d2    (disj* not-p q)
        d3    (disj* p not-q)
        query (conj* not-p not-q)]
    (is (= :true (ao/assumption-truth query [d1 d2 d3])))
    (is (= :unknown (ao/assumption-truth query [d1 d2])))))
