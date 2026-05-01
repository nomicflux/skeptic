(ns skeptic.analysis.origin-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.match :as am]
            [skeptic.analysis.calls :as ac]
            [skeptic.examples]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]
            [skeptic.checking.pipeline :as checking]
            [skeptic.provenance :as prov]
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

(deftest effective-type-entry-shapes-preserve-types-test
  (let [ctx (prov/set-ctx {} tp)
        str-type (atst/T s/Str)
        maybe-str-type (atst/T (s/maybe s/Str))
        root (ao/root-origin 'x maybe-str-type)
        some-assumption (ao/type-predicate-assumption root {:pred :some?} true)]
    (testing "semantic type entries are returned unchanged when no assumptions refine them"
      (is (identical? str-type (ao/effective-type ctx 'x str-type []))))
    (testing "map entries with origins still refine through their origin"
      (is (at/type=? str-type
                     (ao/effective-type ctx
                                        'x
                                        {:type maybe-str-type
                                         :origin root}
                                        [some-assumption]))))
    (testing "fallback entries still produce dyn with the context provenance"
      (let [fallback (ao/effective-type ctx 'missing nil [])]
        (is (at/dyn-type? fallback))
        (is (= :inferred (get-in fallback [:prov :source])))))))

(deftest normalized-origin-private-helpers-match-public-constructors-test
  (let [str-type (atst/T s/Str)]
    (is (= (ao/root-origin 'x str-type)
           (#'ao/root-origin* 'x str-type)))
    (is (= (ao/opaque-origin str-type)
           (#'ao/opaque-origin* str-type)))))

(deftest predicate-info-shapes-satisfy-schema-test
  (let [some-info (ac/type-predicate-assumption-info {:op :var :form 'some?}
                                                     [{:op :local :form 'x}])
        instance-info (ac/type-predicate-assumption-info {:op :var :form 'instance?}
                                                         [{:op :const :val String :form String}
                                                          {:op :local :form 'x}])]
    (is (= {:pred :some?} (s/validate aos/PredInfo some-info)))
    (is (= {:pred :instance? :class String}
           (s/validate aos/PredInfo instance-info)))))

(deftest call-arg-contract-assumptions-test
  (testing "static-call expected arg metadata yields a type-predicate assumption"
    (let [root (atst/analyze-form atst/analysis-dict
                                  '(+ x 1)
                                  {:locals {'x (atst/T s/Any)}})
          assumptions (ao/call-arg-contract-assumptions root)
          assumption (first assumptions)]
      (is (= :static-call (aapi/node-op root)))
      (is (= 1 (count assumptions)))
      (is (= :type-predicate (:kind assumption)))
      (is (= 'x (get-in assumption [:root :sym])))
      (is (= :number? (:pred assumption)))))
  (testing "nil and non-call nodes are no-ops"
    (is (= [] (ao/call-arg-contract-assumptions nil)))
    (is (= [] (ao/call-arg-contract-assumptions {:op :const :val 1 :type (atst/T s/Int)}))))
  (testing "calls without a single classifying input type are no-ops"
    (let [root (atst/analyze-form atst/analysis-dict
                                  '(str x)
                                  {:locals {'x (atst/T s/Any)}})]
      (is (= [] (ao/call-arg-contract-assumptions root))))))

(deftest path-type-predicate-assumption-shape-test
  (let [root-type (atst/T {:x {:k (s/maybe s/Str)}})
        kq-x (amo/exact-key-query tp :x)
        kq-k (amo/exact-key-query tp :k)
        target {:op :keyword-invoke
                :form '(:k (:x x))
                :type (atst/T (s/maybe s/Str))
                :origin (ao/map-key-lookup-origin (ao/root-origin 'x root-type)
                                                  [kq-x kq-k]
                                                  [amo/no-default amo/no-default])}
        some-node {:op :invoke
                   :fn {:op :var :form 'some?}
                   :args [target]}
        assumption (ao/test->assumption tp some-node)]
    (is (= :path-type-predicate (:kind assumption)))
    (is (= 'x (get-in assumption [:root :sym])))
    (is (= 2 (count (:path assumption))))
    (is (= :x (:value (first (:path assumption)))))
    (is (= :k (:value (second (:path assumption)))))
    (is (= :some? (:pred assumption)))
    (is (true? (:polarity assumption)))
    (is (= assumption (s/validate aos/PathTypePredicateAssumption assumption)))))

(deftest branch-origin-with-conjunction-satisfies-schema-test
  (let [root (ao/root-origin 'x (atst/T (s/maybe s/Str)))
        left (ao/type-predicate-assumption root {:pred :some?} true)
        right (ao/type-predicate-assumption root {:pred :string?} true)
        test (ao/conjunction-assumption [left right])
        then-origin (ao/opaque-origin (atst/T s/Str))
        else-origin (ao/opaque-origin (atst/T nil))
        origin (ao/branch-origin test then-origin else-origin)]
    (is (= :branch (:kind origin)))
    (is (= :conjunction (get-in origin [:test :kind])))
    (is (= origin (s/validate aos/Origin origin)))))

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

(deftest opposite-polarity-negates-conjunction-with-truthy-local
  (let [pred-root (ao/root-origin 'pred? (atst/T s/Any))
        m-root (ao/root-origin 'm (atst/T (s/maybe s/Str)))
        pred (ao/truthy-local-assumption pred-root true)
        nil-m (ao/type-predicate-assumption m-root {:pred :nil?} true)
        result (ao/opposite-polarity (ao/conjunction-assumption [pred nil-m]))]
    (is (= :disjunction (:kind result)))
    (is (= [{:kind :truthy-local
             :root pred-root
             :polarity false}
            {:kind :type-predicate
             :root m-root
             :pred :nil?
             :polarity false}]
           (:parts result)))))

(deftest simplify-assumptions-flattens-conjunction
  (let [a (bp 'a true)
        b (bp 'b true)
        c (bp 'c true)
        d (bp 'd true)]
    (is (= [a b c d]
           (ao/simplify-assumptions [a (ao/conjunction-assumption [b c]) d])))))

(deftest simplify-assumptions-reduces-disjunction-to-singleton
  (let [pred-root (ao/root-origin 'pred? (atst/T s/Any))
        m-root (ao/root-origin 'm (atst/T (s/maybe s/Str)))
        pred-true (ao/truthy-local-assumption pred-root true)
        pred-false (assoc pred-true :polarity false)
        non-nil-m (ao/type-predicate-assumption m-root {:pred :nil?} false)
        assumption (ao/disjunction-assumption [pred-false non-nil-m])]
    (is (= [pred-true non-nil-m]
           (ao/simplify-assumptions [pred-true assumption])))))

(deftest simplify-assumptions-marks-zero-survivor-as-contradicted
  (let [pred-root (ao/root-origin 'pred? (atst/T s/Any))
        m-root (ao/root-origin 'm (atst/T (s/maybe s/Str)))
        pred-true (ao/truthy-local-assumption pred-root true)
        pred-false (assoc pred-true :polarity false)
        nil-m (ao/type-predicate-assumption m-root {:pred :nil?} true)
        non-nil-m (assoc nil-m :polarity false)
        assumption (ao/disjunction-assumption [pred-false non-nil-m])]
    (is (= [pred-true nil-m {:kind :contradicted}]
           (ao/simplify-assumptions [pred-true nil-m assumption])))))

(deftest chained-keyword-invoke-yields-path-origin
  (let [root (atst/analyze-form atst/analysis-dict
                                '(:k (:x x))
                                {:locals {'x (atst/T {:x {:k s/Str}})}})
        outer (aapi/find-node root #(= :keyword-invoke (aapi/node-op %)))
        origin (aapi/node-origin outer)]
    (is (= :map-key-lookup (:kind origin)))
    (is (= :root (:kind (:root origin))))
    (is (= 'x (:sym (:root origin))))
    (is (= 2 (count (:path origin))))
    (is (= :x (:value (first (:path origin)))))
    (is (= :k (:value (second (:path origin)))))))

(deftest destructured-projection-binding-origin
  (let [root (atst/analyze-form atst/analysis-dict
                                '(let [{:keys [k]} (:x x)] k)
                                {:locals {'x (atst/T {:x {:k s/Str}})}})
        k-local (aapi/find-node root #(and (= :local (aapi/node-op %))
                                           (= 'k (aapi/node-form %))
                                           (= :static-call (aapi/node-op (:binding-init %)))))
        origin (aapi/node-origin k-local)]
    (is (= :map-key-lookup (:kind origin)))
    (is (= :root (:kind (:root origin))))
    (is (= 'x (:sym (:root origin))))
    (is (= 2 (count (:path origin))))
    (is (= :x (:value (first (:path origin)))))
    (is (= :k (:value (second (:path origin)))))
    (is (at/type=? (atst/T s/Str) (ao/origin-type origin [])))))

(deftest destructure-as-alias-preserves-root-origin
  (let [root (atst/analyze-form atst/analysis-dict
                                '(let [{{:keys [k]} :x :as x} input] x)
                                {:locals {'input (atst/T {:x {:k s/Str}})}})
        x-local (aapi/find-node root #(and (= :local (aapi/node-op %))
                                           (= 'x (aapi/node-form %))
                                           (nil? (:init %))))
        origin (aapi/node-origin x-local)]
    (is (= :root (:kind origin)))
    (is (= 'input (:sym origin)))))

(deftest nested-destructure-double-shim-yields-full-path
  (let [root (atst/analyze-form atst/analysis-dict
                                '(let [{{{:keys [k]} :inner} :x :as x} input] k)
                                {:locals {'input (atst/T {:x {:inner {:k s/Str}}})}})
        k-local (aapi/find-node root #(and (= :local (aapi/node-op %))
                                           (= 'k (aapi/node-form %))
                                           (= :static-call (aapi/node-op (:binding-init %)))))
        origin (aapi/node-origin k-local)]
    (is (= :map-key-lookup (:kind origin)))
    (is (= :root (:kind (:root origin))))
    (is (= 'input (:sym (:root origin))))
    (is (= 3 (count (:path origin))))
    (is (= :x (:value (first (:path origin)))))
    (is (= :inner (:value (second (:path origin)))))
    (is (= :k (:value (nth (:path origin) 2))))))

(deftest origin-type-folds-path
  (let [map-type (atst/T {:x {:k s/Str}})
        root (ao/root-origin 'x map-type)
        kq-x (amo/exact-key-query tp :x)
        kq-k (amo/exact-key-query tp :k)
        origin {:kind :map-key-lookup :root root :path [kq-x kq-k]
                :defaults [amo/no-default amo/no-default]}
        result (ao/origin-type origin [])]
    (is (at/type=? (atst/T s/Str) result))))

(deftest static-get-with-default-yields-path-origin
  (let [root (atst/analyze-form atst/analysis-dict
                                '(let [g (clojure.core/get x :x nil)
                                       k (clojure.core/get g :k nil)]
                                   k)
                                {:locals {'x (atst/T {:x {:k s/Str}})}})
        k-local (aapi/find-node root #(and (= :local (aapi/node-op %))
                                           (= 'k (aapi/node-form %))))
        origin (aapi/node-origin k-local)]
    (is (= :map-key-lookup (:kind origin)))
    (is (= :root (:kind (:root origin))))
    (is (= 'x (:sym (:root origin))))
    (is (= 2 (count (:path origin))))
    (is (= :x (:value (first (:path origin)))))
    (is (= :k (:value (second (:path origin)))))))

(deftest equality-value-assumption-path-shape
  (let [root (atst/analyze-form atst/analysis-dict
                                '(let [{:keys [k]} (:x x)] (= k "b"))
                                {:locals {'x (atst/T {:x {:k s/Str}})}})
        eq-node (aapi/find-node root #(or (and (= :invoke (aapi/node-op %))
                                              (some-> (aapi/call-fn-node %) ac/equality-call?))
                                         (and (= :static-call (aapi/node-op %))
                                              (ac/static-equality-call? %))))
        assumption (ao/test->assumption tp eq-node)]
    (is (= :path-value-equality (:kind assumption)))
    (is (= 'x (get-in assumption [:root :sym])))
    (is (= 2 (count (:path assumption))))
    (is (= :x (:value (first (:path assumption)))))
    (is (= :k (:value (second (:path assumption)))))
    (is (= ["b"] (:values assumption)))
    (is (true? (:polarity assumption)))))

(deftest apply-path-value-equality-refines-root
  (let [root-type (atst/T {:x {:k s/Str}})
        kq-x (amo/exact-key-query tp :x)
        kq-k (amo/exact-key-query tp :k)
        assumption {:kind :path-value-equality
                    :root (ao/root-origin 'x root-type)
                    :path [kq-x kq-k]
                    :values ["b"]
                    :polarity true}
        refined (ao/apply-assumption-to-root-type root-type assumption)
        inner-k (amo/map-get-type (amo/map-get-type refined kq-x) kq-k)]
    (is (at/type=? (atst/T (s/eq "b")) inner-k))))

(deftest branch-local-envs-refines-x-via-nested-equality
  (let [root-type (atst/T {:x {:k s/Str}})
        kq-x (amo/exact-key-query tp :x)
        kq-k (amo/exact-key-query tp :k)
        assumption {:kind :path-value-equality
                    :root (ao/root-origin 'input root-type)
                    :path [kq-x kq-k]
                    :values ["b"]
                    :polarity true}
        x-origin {:kind :map-key-lookup
                  :root (ao/root-origin 'input root-type)
                  :path [kq-x]
                  :defaults [amo/no-default]}
        x-type (ao/origin-type x-origin [assumption])
        inner-k (amo/map-get-type x-type kq-k)]
    (is (at/type=? (atst/T (s/eq "b")) inner-k))
    (is (at/type=? (atst/T {:k (s/eq "b")}) x-type))))

(deftest branch-local-envs-refines-cross-symbol-origin-dependency
  (let [root-type (atst/T {:x {:k s/Str}})
        x-type (atst/T {:k s/Str})
        kq-x (amo/exact-key-query tp :x)
        kq-k (amo/exact-key-query tp :k)
        assumption {:kind :path-value-equality
                    :root (ao/root-origin 'input root-type)
                    :path [kq-x kq-k]
                    :values ["b"]
                    :polarity true}
        x-origin {:kind :map-key-lookup
                  :root (ao/root-origin 'input root-type)
                  :path [kq-x]
                  :defaults [amo/no-default]}
        envs (ao/branch-local-envs tp
                                   {'input {:type root-type
                                            :origin (ao/root-origin 'input root-type)}
                                    'x {:type x-type
                                        :origin x-origin}}
                                   []
                                   {:then-conjuncts [assumption]
                                    :else-conjuncts []})]
    (is (at/type=? (atst/T {:k (s/eq "b")})
                   (get-in envs [:then-locals 'x :type])))
    (is (at/type=? x-type
                   (get-in envs [:else-locals 'x :type])))))

(def ^:private vn-dict (catalog/typed-test-example-entries))
(def ^:private vn-ns 'skeptic.test-examples.var-narrowing)

(defn- vn-form [form] (atst/analyze-form vn-dict form {:ns vn-ns}))

(deftest var-root-origin-attached-test
  (let [root (vn-form 'server)
        origin (aapi/node-origin root)]
    (is (= :root (:kind origin)))
    (is (= 'server (:sym origin)))))

(deftest var-projection-yields-map-key-lookup-origin-test
  (let [root (vn-form '(:host server))
        kw-node (aapi/find-node root #(= :keyword-invoke (aapi/node-op %)))
        origin (aapi/node-origin kw-node)]
    (is (= :map-key-lookup (:kind origin)))
    (is (= :root (:kind (:root origin))))
    (is (= 'server (:sym (:root origin))))
    (is (= 1 (count (:path origin))))
    (is (= :host (:value (first (:path origin)))))))

(deftest some-call-on-var-yields-type-predicate-assumption-test
  (let [root (vn-form '(some? server))
        assumption (ao/test->assumption tp root)]
    (is (= :type-predicate (:kind assumption)))
    (is (= :some? (:pred assumption)))
    (is (= 'server (get-in assumption [:root :sym])))))

(deftest when-truthy-on-var-yields-truthy-local-assumption-test
  (let [root (vn-form 'server)
        assumption (ao/test->assumption tp root)]
    (is (= :truthy-local (:kind assumption)))
    (is (true? (:polarity assumption)))
    (is (= 'server (get-in assumption [:root :sym])))))

(deftest equality-on-var-yields-value-equality-assumption-test
  (let [root (vn-form '(= server :foo))
        assumption (ao/test->assumption tp root)]
    (is (= :value-equality (:kind assumption)))
    (is (= 'server (get-in assumption [:root :sym])))
    (is (= [:foo] (:values assumption)))))

(deftest blank-call-on-var-yields-blank-check-assumption-test
  (let [var-targ {:op :var :form 'server-str :type (atst/T s/Str)
                  :origin (ao/root-origin 'server-str (atst/T s/Str))}
        invoke-node {:op :invoke
                     :fn {:op :var :form 'clojure.string/blank?}
                     :args [var-targ]}
        assumption (ao/test->assumption tp invoke-node)]
    (is (= :blank-check (:kind assumption)))
    (is (= 'server-str (get-in assumption [:root :sym])))))

(deftest let-aliased-var-preserves-root-test
  (let [root (vn-form '(let [s server] s))
        s-local (aapi/find-node root #(and (= :local (aapi/node-op %))
                                           (= 's (aapi/node-form %))))
        origin (aapi/node-origin s-local)]
    (is (= :root (:kind origin)))
    (is (= 'server (:sym origin)))))

(deftest case-on-var-projection-narrows-test
  (let [var-node {:op :var :form 'server :type (atst/T {:host s/Str :port s/Int})
                  :origin (ao/root-origin 'server (atst/T {:host s/Str :port s/Int}))}
        key-node {:op :const :val :host :form :host}
        get-node {:op :static-call :fn {:op :var :form 'clojure.lang.RT/get}
                  :args [var-node key-node]
                  :class clojure.lang.RT :method 'get}
        result (am/case-kw-and-target get-node)]
    (is (some? result))
    (is (= :host (first result)))
    (is (= 'server (aapi/node-form (second result))))))

(deftest do-forwards-ret-origin-test
  (let [local-origin (ao/root-origin 'some-local (atst/T s/Str))
        root (atst/analyze-form atst/analysis-dict
                                '(do (println :x) some-local)
                                {:locals {'some-local {:type (atst/T s/Str)
                                                       :origin local-origin}}})
        origin (aapi/node-origin root)]
    (is (= :do (aapi/node-op root)))
    (is (= :root (:kind origin)))
    (is (= 'some-local (:sym origin)))))

(deftest try-zero-catch-forwards-body-origin-test
  (let [local-origin (ao/root-origin 'some-local (atst/T s/Str))
        root (atst/analyze-form atst/analysis-dict
                                '(try some-local (finally (cleanup!)))
                                {:locals {'some-local {:type (atst/T s/Str)
                                                       :origin local-origin}
                                          'cleanup! {:type (atst/T s/Any)}}})
        origin (aapi/node-origin root)]
    (is (= :try (aapi/node-op root)))
    (is (= :root (:kind origin)))
    (is (= 'some-local (:sym origin)))))

(deftest try-with-catch-stays-opaque-test
  (let [local-origin (ao/root-origin 'some-local (atst/T s/Str))
        root (atst/analyze-form atst/analysis-dict
                                '(try some-local (catch Exception _ :default))
                                {:locals {'some-local {:type (atst/T s/Str)
                                                       :origin local-origin}}})
        origin (aapi/node-origin root)]
    (is (= :try (aapi/node-op root)))
    (is (nil? origin))))

(deftest with-meta-forwards-expr-origin-test
  (let [local-origin (ao/root-origin 'some-local (atst/T s/Str))
        form (with-meta 'some-local {:foo 1})
        root (atst/analyze-form atst/analysis-dict
                                form
                                {:locals {'some-local {:type (atst/T s/Str)
                                                       :origin local-origin}}})
        origin (aapi/node-origin root)]
    (is (= :root (:kind origin)))
    (is (= 'some-local (:sym origin)))))
