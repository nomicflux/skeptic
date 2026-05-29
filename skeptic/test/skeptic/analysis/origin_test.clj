(ns skeptic.analysis.origin-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.match :as am]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.calls :as ac]
            [skeptic.examples]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.origin :as ao]
            [skeptic.analysis.origin.schema :as aos]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis.types :as at]
            [skeptic.analysis-test :as atst]
            [skeptic.provenance :as prov]
            [skeptic.test-examples.catalog :as catalog]
            [skeptic.test-helpers :refer [is-type= T tp some!]]
            [skeptic.test-support.shared-worker :as shared-worker]
            [skeptic.typed-decls :as typed-decls])
  (:import [clojure.lang Numbers]))

(use-fixtures :once shared-worker/with-shared-worker)

(def ^:private oc-dict (catalog/typed-test-example-entries))
(def ^:private oc-ns 'skeptic.test-examples.origin-cases)

(defn- oc-asts []
  (aat/analyze-ns-file oc-dict oc-ns (atst/fixture-file-for-ns oc-ns) {}))

(defn- oc-body
  "Returns the value-node body of origin-cases fixture def `name`. The body is
   the form the former injected-locals `analyze-form` probe wrapped."
  [name]
  (let [def-node (atst/ast-by-name (oc-asts) name)
        method (first (aapi/def-fn-methods def-node))]
    (aapi/method-body method)))

(deftest origin-constructors-unit-test
  (testing "opaque origin tags normalized type"
    (is (= :opaque (:kind (ao/opaque-origin (T s/Int))))))
  (testing "root origin carries sym and type"
    (let [o (ao/root-origin 'x (T s/Str))]
      (is (= :root (:kind o)))
      (is (= 'x (:sym o)))
      (is-type= (T s/Str) (:type o))))
  (testing "effective-type returns refined type"
    (is-type= (T s/Int) (ao/effective-type tp 'x (T s/Int) []))))

(deftest effective-type-entry-shapes-preserve-types-test
  (let [ctx (prov/set-ctx {} tp)
        str-type (T s/Str)
        maybe-str-type (T (s/maybe s/Str))
        root (ao/root-origin 'x maybe-str-type)
        some-assumption (ao/type-predicate-assumption root {:pred :some?} true)]
    (testing "semantic type entries are returned unchanged when no assumptions refine them"
      (is (identical? str-type (ao/effective-type ctx 'x str-type []))))
    (testing "map entries with origins still refine through their origin"
      (is-type= str-type
          (ao/effective-type ctx
                             'x
                             {:type maybe-str-type
                              :origin root}
                             [some-assumption])))
    (testing "fallback entries still produce dyn with the context provenance"
      (let [fallback (ao/effective-type ctx 'missing nil [])]
        (is (at/dyn-type? fallback))
        (is (= :inferred (get-in fallback [:prov :source])))))))

(deftest normalized-origin-private-helpers-match-public-constructors-test
  (let [str-type (T s/Str)]
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
    (is (= {:pred :instance? :class (oracle/host-handle String)}
           (s/validate aos/PredInfo instance-info)))))

(deftest call-arg-contract-assumptions-test
  (testing "static-call expected arg metadata yields a type-predicate assumption"
    (let [root (oc-body 'oc-plus-x-1)
          assumptions (ao/call-arg-contract-assumptions root)
          assumption (first assumptions)]
      (is (= :static-call (aapi/node-op root)))
      (is (= 1 (count assumptions)))
      (is (= :type-predicate (:kind assumption)))
      (is (= 'x (get-in assumption [:root :sym])))
      (is (= :number? (:pred assumption)))))
  (testing "nil and non-call nodes are no-ops"
    (is (= [] (ao/call-arg-contract-assumptions nil)))
    (is (= [] (ao/call-arg-contract-assumptions {:op :const :form 1 :val 1 :type (T s/Int)}))))
  (testing "calls without a single classifying input type are no-ops"
    (let [root (oc-body 'oc-str-x)]
      (is (= [] (ao/call-arg-contract-assumptions root))))))

(deftest path-type-predicate-assumption-shape-test
  (let [root-type (T {:x {:k (s/maybe s/Str)}})
        kq-x (amo/exact-key-query tp :x)
        kq-k (amo/exact-key-query tp :k)
        target {:op :keyword-invoke
                :form '(:k (:x x))
                :type (T (s/maybe s/Str))
                :origin (ao/map-key-lookup-origin (ao/root-origin 'x root-type)
                                                  [kq-x kq-k]
                                                  [amo/no-default amo/no-default])}
        some-node {:op :invoke
                   :form '(some? (:k (:x x)))
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
  (let [root (ao/root-origin 'x (T (s/maybe s/Str)))
        left (ao/type-predicate-assumption root {:pred :some?} true)
        right (ao/type-predicate-assumption root {:pred :string?} true)
        test (ao/conjunction-assumption [left right])
        then-origin (ao/opaque-origin (T s/Str))
        else-origin (ao/opaque-origin (T nil))
        origin (ao/branch-origin test then-origin else-origin)]
    (is (= :branch (:kind origin)))
    (is (= :conjunction (get-in origin [:test :kind])))
    (is (= origin (s/validate aos/Origin origin)))))

(deftest typed-binding-and-refinement-test
  (testing "let-driven flow through or expands to refinable branch"
    (let [or-let (oc-body 'oc-or-let)]
      (is-type= (T s/Int) (aapi/node-type or-let))
      (is (aapi/find-node or-let #(and (= :if (aapi/node-op %))
                                       (at/type=? (T s/Int) (aapi/node-type %)))))))
  (testing "if refinement and joins"
    (let [literal-if (oc-body 'oc-literal-if)
          local-if (oc-body 'oc-local-if)
          maybe-if (oc-body 'oc-maybe-if)
          or-form (oc-body 'oc-or-form)]
      (is-type= (T (sb/join s/Bool s/Str)) (aapi/node-type literal-if))
      (is-type= (T s/Int) (aapi/node-type local-if))
      (is-type= (T s/Int) (aapi/node-type maybe-if))
      (is (= :let (aapi/node-op or-form)))
      (is-type= (T s/Int) (aapi/node-type or-form)))))

(deftest attach-type-branch-refinement-test
  (testing "or/let typed setup exposes branch join on inner if"
    (let [root (oc-body 'oc-or-let)]
      (is-type= (T s/Int) (aapi/node-type root))
      (is (aapi/find-node root #(and (= :if (aapi/node-op %))
                                     (at/type=? (T s/Int) (aapi/node-type %)))))))
  (testing "literal if join"
    (let [root (oc-body 'oc-literal-if)]
      (is-type= (T (sb/join s/Bool s/Str)) (aapi/node-type root))))
  (testing "symbol test if keeps output type when branches agree"
    (let [root (oc-body 'oc-local-if)]
      (is-type= (T s/Int) (aapi/node-type root))))
  (testing "maybe-refinement if joins nilable branch with default"
    (let [root (oc-body 'oc-maybe-if)]
      (is-type= (T s/Int) (aapi/node-type root))))
  (testing "or macro matches expanded branch join"
    (let [root (oc-body 'oc-or-form)]
      (is (= :let (aapi/node-op root)))
      (is-type= (T s/Int) (aapi/node-type root)))))

(deftest branch-resolution-joins-test
  (testing "branch joins stay branch-local and nil-bearing joins canonicalize to maybe"
    (let [test-dict (catalog/typed-test-example-entries)
          example-dict (:dict (typed-decls/typed-ns-results {} 'skeptic.examples :clj
                                                            (java.io.File. "src/skeptic/examples.clj") nil))
          control-flow-defs (atst/resolved-defs-of test-dict 'skeptic.test-examples.control-flow)
          resolution-defs (atst/resolved-defs-of test-dict 'skeptic.test-examples.resolution)
          example-defs (->> (aat/analyze-ns-file example-dict 'skeptic.examples atst/examples-file {})
                            (keep #(aapi/analyzed-def-entry 'skeptic.examples %))
                            (into {}))]
      (is-type= (T (sb/join s/Int s/Str))
          (aapi/resolved-def-output-type control-flow-defs
                                         'skeptic.test-examples.control-flow/sample-if-mixed-fn))
      (is-type= (T s/Int)
          (aapi/resolved-def-output-type resolution-defs
                                         'skeptic.test-examples.resolution/flat-multi-step-g))
      (is-type= (T (s/maybe s/Int))
          (aapi/resolved-def-output-type example-defs
                                         'skeptic.examples/flat-maybe-multi-step-f))
      (is-type= (T {:value (s/maybe s/Int)})
          (aapi/resolved-def-output-type example-defs
                                         'skeptic.examples/nested-maybe-multi-step-f)))))

(deftest region-conjuncts-and-shape-two-some-test
  (testing "let+if and-shape collects each some? conjunct on the truthy side and emits a disjunction of negations on the falsy side"
    (let [root (oc-body 'oc-and-some)
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
    (let [root (oc-body 'oc-eq-local-literal)
          assumption (ao/test->assumption tp root)]
      (is (= :value-equality (:kind assumption)))
      (is (= 'x (get-in assumption [:root :sym])))
      (is (= [:a] (:values assumption)))))
  (testing "literal equals local"
    (let [root (oc-body 'oc-eq-literal-local)
          assumption (ao/test->assumption tp root)]
      (is (= :value-equality (:kind assumption)))
      (is (= 'x (get-in assumption [:root :sym])))
      (is (= [:a] (:values assumption))))))

(deftest let-shadow-nil-check-root-origin-some-to-lambda-shape-test
  (testing "shadowed let + param alias: nil? on shadow name still gets :root so outer else refines unary -"
    (let [root (oc-body 'oc-shadow-nil-check)
          minus (aapi/find-node root
                                #(and (= :static-call (aapi/node-op %))
                                      (at/class-equals? (oracle/host-handle Numbers) (aapi/node-class %))
                                      (= 'minus (aapi/node-method %))))]
      (is (some? minus) "expected unary - lowered to Numbers/minus")
      (is-type= atst/numeric-dyn (first (aapi/call-actual-argtypes minus))))))

(deftest negated-assumptions-and-narrowing-alias-roots-test
  (testing "not around nil? inverts the branch assumption"
    (let [root (oc-body 'oc-not-nil-if)
          if-node (aapi/find-node root #(= :if (aapi/node-op %)))
          then-x (aapi/find-node (aapi/then-node if-node)
                                 #(and (= :local (aapi/node-op %))
                                       (= 'x (aapi/node-form %))))]
      (is-type= (T s/Str) (aapi/node-type if-node))
      (is-type= (T s/Str) (aapi/node-type then-x))))

  (testing "narrowing-preserving aliases get their own root for later refinement"
    (let [root (oc-body 'oc-narrowing-alias)
          then-p (aapi/find-node root
                                 #(and (= :local (aapi/node-op %))
                                       (= 'p (aapi/node-form %))
                                       (= (T s/Str) (aapi/node-type %))))]
      (is-type= (T s/Str) (aapi/node-type then-p))
      (is (= 'p (:sym (ao/local-root-origin tp then-p)))))))

(deftest guarded-keys-maybe-s-caller-origin-test
  (let [dict (catalog/typed-test-example-entries)
        resolved (aat/analyze-ns-file dict 'skeptic.test-examples.nullability
                                       (atst/fixture-file-for-ns 'skeptic.test-examples.nullability) {})
        ast (atst/ast-by-name resolved 'guarded-keys-caller)
        guarded-if (aapi/find-node ast #(and (= :if (aapi/node-op %))
                                             (= 'pair (aapi/node-form (aapi/node-test %)))))
        pair-assumption (some! (aapi/branch-test-assumption guarded-if))
        lookup-nodes (filter #(and (= :static-call (aapi/node-op %))
                                   (at/class-equals? (oracle/host-handle clojure.lang.RT) (aapi/node-class %))
                                   (= 'get (aapi/node-method %)))
                             (aapi/annotated-nodes ast))]
    (is (= 'pair (get-in pair-assumption [:root :sym])))
    (is (= :truthy-local (:kind pair-assumption)))
    (is (= 2 (count lookup-nodes)))
    (doseq [lookup lookup-nodes]
      (let [origin (some! (aapi/node-origin lookup))]
        (is (= :map-key-lookup (:kind origin)))
        (is-type= (T s/Str)
            (ao/origin-type origin [pair-assumption]))))))

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
  (let [root (oc-body 'oc-and-pos)
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
  (let [pred-root (ao/root-origin 'pred? (T s/Any))
        m-root (ao/root-origin 'm (T (s/maybe s/Str)))
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

(deftest opposite-polarity-negates-disjunction-to-conjunction
  (let [mx-root (ao/root-origin 'mx (T (s/maybe s/Int)))
        my-root (ao/root-origin 'my (T (s/maybe s/Int)))
        nil-mx (ao/type-predicate-assumption mx-root {:pred :nil?} true)
        nil-my (ao/type-predicate-assumption my-root {:pred :nil?} true)
        result (ao/opposite-polarity (ao/disjunction-assumption [nil-mx nil-my]))]
    (is (= :conjunction (:kind result)))
    (is (= [{:kind :type-predicate
             :root mx-root
             :pred :nil?
             :polarity false}
            {:kind :type-predicate
             :root my-root
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
  (let [pred-root (ao/root-origin 'pred? (T s/Any))
        m-root (ao/root-origin 'm (T (s/maybe s/Str)))
        pred-true (ao/truthy-local-assumption pred-root true)
        pred-false (assoc pred-true :polarity false)
        non-nil-m (ao/type-predicate-assumption m-root {:pred :nil?} false)
        assumption (ao/disjunction-assumption [pred-false non-nil-m])]
    (is (= [pred-true non-nil-m]
           (ao/simplify-assumptions [pred-true assumption])))))

(deftest simplify-assumptions-marks-zero-survivor-as-contradicted
  (let [pred-root (ao/root-origin 'pred? (T s/Any))
        m-root (ao/root-origin 'm (T (s/maybe s/Str)))
        pred-true (ao/truthy-local-assumption pred-root true)
        pred-false (assoc pred-true :polarity false)
        nil-m (ao/type-predicate-assumption m-root {:pred :nil?} true)
        non-nil-m (assoc nil-m :polarity false)
        assumption (ao/disjunction-assumption [pred-false non-nil-m])]
    (is (= [pred-true nil-m {:kind :contradicted}]
           (ao/simplify-assumptions [pred-true nil-m assumption])))))

(deftest chained-keyword-invoke-yields-path-origin
  (let [root (oc-body 'oc-chained-kw)
        outer (aapi/find-node root #(= :keyword-invoke (aapi/node-op %)))
        origin (aapi/node-origin outer)]
    (is (= :map-key-lookup (:kind origin)))
    (is (= :root (:kind (:root origin))))
    (is (= 'x (:sym (:root origin))))
    (is (= 2 (count (:path origin))))
    (is (= :x (:value (first (:path origin)))))
    (is (= :k (:value (second (:path origin)))))))

(deftest destructured-projection-binding-origin
  (let [root (oc-body 'oc-destructure-projection)
        k-local (aapi/find-node root #(and (= :local (aapi/node-op %))
                                           (= 'k (aapi/node-form %))
                                           (= :static-call (aapi/node-op (:binding-init %)))))
        origin (some! (aapi/node-origin k-local))]
    (is (= :map-key-lookup (:kind origin)))
    (is (= :root (:kind (:root origin))))
    (is (= 'x (:sym (:root origin))))
    (is (= 2 (count (:path origin))))
    (is (= :x (:value (first (:path origin)))))
    (is (= :k (:value (second (:path origin)))))
    (is-type= (T s/Str) (ao/origin-type origin []))))

(deftest destructure-as-alias-preserves-root-origin
  (let [root (oc-body 'oc-destructure-as-alias)
        x-local (aapi/find-node root #(and (= :local (aapi/node-op %))
                                           (= 'x (aapi/node-form %))
                                           (nil? (:init %))))
        origin (aapi/node-origin x-local)]
    (is (= :root (:kind origin)))
    (is (= 'input (:sym origin)))))

(deftest nested-destructure-double-shim-yields-full-path
  (let [root (oc-body 'oc-nested-destructure)
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
  (let [map-type (T {:x {:k s/Str}})
        root (ao/root-origin 'x map-type)
        kq-x (amo/exact-key-query tp :x)
        kq-k (amo/exact-key-query tp :k)
        origin {:kind :map-key-lookup :root root :path [kq-x kq-k]
                :defaults [amo/no-default amo/no-default]}
        result (ao/origin-type origin [])]
    (is-type= (T s/Str) result)))

(deftest static-get-with-default-yields-path-origin
  (let [root (oc-body 'oc-static-get-default)
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
  (let [root (oc-body 'oc-eq-path)
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
  (let [root-type (T {:x {:k s/Str}})
        kq-x (amo/exact-key-query tp :x)
        kq-k (amo/exact-key-query tp :k)
        assumption {:kind :path-value-equality
                    :root (ao/root-origin 'x root-type)
                    :path [kq-x kq-k]
                    :values ["b"]
                    :polarity true}
        refined (ao/apply-assumption-to-root-type root-type assumption)
        inner-k (amo/map-get-type (some! (amo/map-get-type refined kq-x)) kq-k)]
    (is-type= (T (s/eq "b")) inner-k)))

(deftest branch-local-envs-refines-x-via-nested-equality
  (let [root-type (T {:x {:k s/Str}})
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
    (is-type= (T (s/eq "b")) inner-k)
    (is-type= (T {:k (s/eq "b")}) x-type)))

(deftest branch-local-envs-refines-cross-symbol-origin-dependency
  (let [root-type (T {:x {:k s/Str}})
        x-type (T {:k s/Str})
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
    (is-type= (T {:k (s/eq "b")})
        (get-in envs [:then-locals 'x :type]))
    (is-type= x-type
        (get-in envs [:else-locals 'x :type]))))

(def ^:private vn-dict (catalog/typed-test-example-entries))
(def ^:private vn-ns 'skeptic.test-examples.var-narrowing)

(defn- vn-def
  "Worker-analyzes the var-narrowing fixture ns and returns the value-node body
   of fixture def `name` (an `(s/defn name [] <form>)`)."
  [name]
  (let [asts (aat/analyze-ns-file vn-dict vn-ns (atst/fixture-file-for-ns vn-ns) {})
        def-node (atst/ast-by-name asts name)
        method (first (aapi/def-fn-methods def-node))]
    (aapi/method-body method)))

(deftest var-root-origin-attached-test
  (let [root (vn-def 'vn-server-root)
        origin (aapi/node-origin root)]
    (is (= :root (:kind origin)))
    (is (= 'server (:sym origin)))))

(deftest var-projection-yields-map-key-lookup-origin-test
  (let [root (vn-def 'vn-server-host-projection)
        kw-node (aapi/find-node root #(= :keyword-invoke (aapi/node-op %)))
        origin (aapi/node-origin kw-node)]
    (is (= :map-key-lookup (:kind origin)))
    (is (= :root (:kind (:root origin))))
    (is (= 'server (:sym (:root origin))))
    (is (= 1 (count (:path origin))))
    (is (= :host (:value (first (:path origin)))))))

(deftest some-call-on-var-yields-type-predicate-assumption-test
  (let [root (vn-def 'vn-server-some-call)
        assumption (ao/test->assumption tp root)]
    (is (= :type-predicate (:kind assumption)))
    (is (= :some? (:pred assumption)))
    (is (= 'server (get-in assumption [:root :sym])))))

(deftest when-truthy-on-var-yields-truthy-local-assumption-test
  (let [root (vn-def 'vn-server-root)
        assumption (ao/test->assumption tp root)]
    (is (= :truthy-local (:kind assumption)))
    (is (true? (:polarity assumption)))
    (is (= 'server (get-in assumption [:root :sym])))))

(deftest equality-on-var-yields-value-equality-assumption-test
  (let [root (vn-def 'vn-server-equality)
        assumption (ao/test->assumption tp root)]
    (is (= :value-equality (:kind assumption)))
    (is (= 'server (get-in assumption [:root :sym])))
    (is (= [:foo] (:values assumption)))))

(deftest blank-call-on-var-yields-blank-check-assumption-test
  (let [var-targ {:op :var :form 'server-str :type (T s/Str)
                  :origin (ao/root-origin 'server-str (T s/Str))}
        invoke-node {:op :invoke
                     :form '(clojure.string/blank? server-str)
                     :fn {:op :var :form 'clojure.string/blank?}
                     :args [var-targ]}
        assumption (ao/test->assumption tp invoke-node)]
    (is (= :blank-check (:kind assumption)))
    (is (= 'server-str (get-in assumption [:root :sym])))))

(deftest let-aliased-var-preserves-root-test
  (let [root (vn-def 'vn-server-let-alias)
        s-local (aapi/find-node root #(and (= :local (aapi/node-op %))
                                           (= 's (aapi/node-form %))))
        origin (aapi/node-origin s-local)]
    (is (= :root (:kind origin)))
    (is (= 'server (:sym origin)))))

(deftest case-on-var-projection-narrows-test
  (let [var-node {:op :var :form 'server :type (T {:host s/Str :port s/Int})
                  :origin (ao/root-origin 'server (T {:host s/Str :port s/Int}))}
        key-node {:op :const :val :host :form :host}
        get-node {:op :static-call
                  :form '(. clojure.lang.RT (get server :host))
                  :fn {:op :var :form 'clojure.lang.RT/get}
                  :args [var-node key-node]
                  :class (oracle/host-handle clojure.lang.RT) :method 'get}
        result (am/case-kw-and-target get-node)]
    (is (some? result))
    (is (= :host (first result)))
    (is (= 'server (aapi/node-form (second result))))))

(deftest do-forwards-ret-origin-test
  (let [root (oc-body 'oc-do-forwards)
        origin (aapi/node-origin root)]
    (is (= :do (aapi/node-op root)))
    (is (= :root (:kind origin)))
    (is (= 'some-local (:sym origin)))))

(deftest try-zero-catch-forwards-body-origin-test
  (let [root (oc-body 'oc-try-finally)
        origin (aapi/node-origin root)]
    (is (= :try (aapi/node-op root)))
    (is (= :root (:kind origin)))
    (is (= 'some-local (:sym origin)))))

(deftest try-with-catch-stays-opaque-test
  (let [root (oc-body 'oc-try-catch)
        origin (aapi/node-origin root)]
    (is (= :try (aapi/node-op root)))
    (is (nil? origin))))

(deftest with-meta-forwards-expr-origin-test
  (let [root (oc-body 'oc-with-meta)
        origin (aapi/node-origin root)]
    (is (= :root (:kind origin)))
    (is (= 'some-local (:sym origin)))))
