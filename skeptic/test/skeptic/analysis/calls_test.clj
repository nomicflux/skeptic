(ns skeptic.analysis.calls-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.test-api :as aat]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis-test :as atst]
            [skeptic.checking.pipeline :as checking]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.static-call-examples])
  (:import [java.io File]))

(def static-call-examples-file (File. "src/skeptic/static_call_examples.clj"))

(def static-call-dict
  (merge (:dict (typed-decls/typed-ns-results {} 'skeptic.static-call-examples))
         {'user (atst/T skeptic.static-call-examples/UserDesc)
          'counts (atst/T skeptic.static-call-examples/MaybeCount)
          'left (atst/T skeptic.static-call-examples/LeftFields)
          'right (atst/T skeptic.static-call-examples/RightFields)}))

(defn assert-typed-call-metadata-only
  [node]
  (is (some? (aapi/node-type node)))
  (is (vector? (aapi/call-actual-argtypes node)))
  (is (aapi/typed-call-metadata-only? node)))

(deftest calls-predicate-and-qualify-unit-test
  (testing "qualify-symbol"
    (is (nil? (ac/qualify-symbol nil nil)))
    (is (= 'already/qualified (ac/qualify-symbol 'foo 'already/qualified)))
    (is (= 'my.ns/x (ac/qualify-symbol 'my.ns 'x)))
    (is (= 'x (ac/qualify-symbol nil 'x))))
  (testing "call-shape predicates on synthetic fn nodes"
    (is (ac/merge-call? (aat/test-fn-node 'merge)))
    (is (ac/contains-call? (aat/test-fn-node 'contains?)))
    (is (ac/get-call? (aat/test-fn-node 'get)))
    (is (ac/static-get-call? (aat/test-static-call-node clojure.lang.RT 'get)))
    (is (ac/static-merge-call? (aat/test-static-call-node clojure.lang.RT 'merge)))
    (is (ac/static-contains-call? (aat/test-static-call-node clojure.lang.RT 'contains?)))))

(deftest invoke-and-static-application-roots-test
  (testing "do, static-call, and invoke roots"
    (let [do-form (atst/analyze-form '(do (str "hello") (+ 1 2)))
          plus-form (atst/analyze-form '(+ 1 x) (atst/locals 'x))
          local-invoke (atst/analyze-form '(f)
                                          (atst/local-types {'f {:type (atst/T (s/make-fn-schema s/Int [[]]))
                                                                 :output-type (atst/T s/Int)
                                                                 :arglists {0 {:arglist []
                                                                               :count 0
                                                                               :types []}}}}))
          nested-invoke (atst/analyze-form '((f 1) 3 4)
                                           (atst/locals 'f))]
      (is (= :do (aapi/node-op do-form)))
      (is (= :static-call (aapi/node-op plus-form)))
      (is (= :invoke (aapi/node-op local-invoke)))
      (is (= :invoke (aapi/node-op nested-invoke))))))

(deftest typed-application-call-test
  (testing "application types"
    (let [dynamic-call (atst/analyze-form '(+ 1 2))
          unknown-invoke (atst/analyze-form '(f 1 2)
                                            (atst/locals 'f))
          known-call (atst/analyze-form '(skeptic.test-examples.basics/int-add 1 2))]
      (is (= [(atst/T s/Int) (atst/T s/Int)] (aapi/call-actual-argtypes dynamic-call)))
      (is (= atst/numeric-dyn (aapi/node-type dynamic-call)))
      (assert-typed-call-metadata-only dynamic-call)
      (is (= (atst/T (s/make-fn-schema s/Any [[s/Any s/Any]]))
             (aapi/node-fn-type unknown-invoke)))
      (assert-typed-call-metadata-only unknown-invoke)
      (is (= [(atst/T s/Int) (atst/T s/Int)] (aapi/call-actual-argtypes known-call)))
      (is (= [(atst/T s/Int) (atst/T s/Int)] (aapi/call-expected-argtypes known-call)))
      (is (= (atst/T s/Int) (aapi/node-type known-call)))
      (assert-typed-call-metadata-only known-call))))

(deftest analyse-application-test
  (testing "original partially unknown application setup"
    (let [root (atst/analyze-form '(+ 1 x))]
      (is (= :static-call (aapi/node-op root)))
      (is (= [(atst/T s/Int) (atst/T s/Any)] (aapi/call-actual-argtypes root)))))
  (testing "original zero-arity application setup"
    (let [root (atst/analyze-form '(f))]
      (is (= :invoke (aapi/node-op root)))
      (is (= 0 (count (aapi/call-actual-argtypes root))))
      (assert-typed-call-metadata-only root)))
  (testing "original nested application setup"
    (let [root (atst/analyze-form '((f 1) 3 4))]
      (is (= :invoke (aapi/node-op root)))
      (is (= 2 (count (aapi/call-actual-argtypes root))))
      (is (= '(f 1) (aapi/node-form (aapi/call-fn-node root))))
      (assert-typed-call-metadata-only root))))

(deftest attach-type-info-application-test
  (testing "original generic application typed setup"
    (let [root (atst/analyze-form {} '(+ 1 2))]
      (is (= [(atst/T s/Int) (atst/T s/Int)] (aapi/call-actual-argtypes root)))
      (is (= atst/numeric-dyn (aapi/node-type root)))
      (assert-typed-call-metadata-only root)))
  (testing "original known application typed setup"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(skeptic.test-examples.basics/int-add 1 2))]
      (is (= [(atst/T s/Int) (atst/T s/Int)] (aapi/call-actual-argtypes root)))
      (is (= [(atst/T s/Int) (atst/T s/Int)] (aapi/call-expected-argtypes root)))
      (is (= (atst/T s/Int) (aapi/node-type root)))
      (assert-typed-call-metadata-only root))))

(deftest canonicalized-callable-entry-test
  (let [symbol-type (typed-decls/desc->type
                     {:name "f"
                      :schema (s/make-fn-schema clojure.lang.Symbol
                                                [[(s/one java.lang.String 'arg)]])})
        keyword-type (typed-decls/desc->type
                      {:name "f"
                       :schema (s/make-fn-schema clojure.lang.Keyword
                                                 [[(s/one s/Any 'arg)]])})
        int-type (typed-decls/desc->type
                  {:name "f"
                   :schema (s/make-fn-schema java.lang.Integer
                                             [[(s/one s/Any 'arg)]])})
        symbol-call (atst/analyze-form {}
                                      '(f "x")
                                      {:ns 'skeptic.analysis-test
                                       :locals {'f symbol-type}})
        keyword-call (atst/analyze-form {}
                                       '(f :x)
                                       {:ns 'skeptic.analysis-test
                                        :locals {'f keyword-type}})
        int-call (atst/analyze-form {}
                                   '(f :x)
                                   {:ns 'skeptic.analysis-test
                                    :locals {'f int-type}})
        quoted-symbol (atst/analyze-form '(quote foo))]
    (is (= (atst/T s/Symbol) (aapi/node-type symbol-call)))
    (is (= [(atst/T s/Str)] (aapi/call-expected-argtypes symbol-call)))
    (is (= (atst/T s/Keyword) (aapi/node-type keyword-call)))
    (is (= (atst/T s/Int) (aapi/node-type int-call)))
    (is (= (atst/T s/Symbol) (aapi/node-type quoted-symbol)))
    (assert-typed-call-metadata-only symbol-call)
    (assert-typed-call-metadata-only keyword-call)
    (assert-typed-call-metadata-only int-call)))

(deftest static-call-analysis-test
  (testing "get returns declared field types from typed maps"
      (let [required-get (atst/analyze-form static-call-dict
                                            '(get user :name)
                                            {:ns 'skeptic.analysis-test
                                             :locals {'user {:type (atst/T skeptic.static-call-examples/UserDesc)}}})
          optional-get (atst/analyze-form static-call-dict
                                          '(get user :nickname)
                                          {:ns 'skeptic.analysis-test
                                           :locals {'user {:type (atst/T skeptic.static-call-examples/UserDesc)}}})
          defaulted-get (atst/analyze-form static-call-dict
                                           '(get counts :count "zero")
                                           {:ns 'skeptic.analysis-test
                                            :locals {'counts {:type (atst/T skeptic.static-call-examples/MaybeCount)}}})]
      (is (= (atst/T s/Str) (aapi/node-type required-get)))
      (is (= (atst/T (s/maybe s/Str)) (aapi/node-type optional-get)))
      (is (= (atst/T (sb/join s/Int s/Str))
             (aapi/node-type defaulted-get)))))

  (testing "merge returns merged typed maps"
    (let [merged (atst/analyze-form static-call-dict
                                    '(merge left right)
                                    {:ns 'skeptic.analysis-test
                                     :locals {'left {:type (atst/T skeptic.static-call-examples/LeftFields)}
                                              'right {:type (atst/T skeptic.static-call-examples/RightFields)}}})]
      (is (= (atst/T {:a s/Int :b s/Int})
             (aapi/node-type merged)))))

  (testing "rebuilt maps stay in semantic map format"
    (let [root (atst/analyze-form static-call-dict
                                  '{:name (get user :name)
                                    :nickname (get user :nickname)}
                                  {:ns 'skeptic.analysis-test
                                   :locals {'user {:type (atst/T skeptic.static-call-examples/UserDesc)}}})]
      (is (= (atst/T {:name s/Str
                      :nickname (s/maybe s/Str)})
             (aapi/node-type root))))))

(deftest resolved-static-get-feeds-parent-call-test
  (testing "resolved static get feeds final reduced field types into parent calls"
    (let [dict (:dict (typed-decls/typed-ns-results {} 'skeptic.static-call-examples))
          {:keys [resolved]} (checking/analyze-source-exprs dict
                                                            'skeptic.static-call-examples
                                                            static-call-examples-file
                                                            (atst/source-exprs-in 'skeptic.static-call-examples static-call-examples-file))
          failure-ast (atst/ast-by-name resolved 'nested-multi-step-failure)
          call-node (atst/node-by-form failure-ast '(nested-multi-step-takes-str (get (nested-multi-step-g) :value)))]
      (is (= [(atst/T s/Int)] (aapi/call-actual-argtypes call-node)))
      (assert-typed-call-metadata-only call-node))))

(deftest attach-type-info-local-fn-invocation-test
  (testing "local fn invocation through int-add"
    (let [root (atst/analyze-form atst/typed-test-examples-dict
                                  '(let [f (fn [x] nil)]
                                     (skeptic.test-examples.basics/int-add 1 (f x))))]
      (is (= (atst/T s/Int) (aapi/node-type root)))
      (is (= (atst/T (s/eq nil))
             (aapi/node-type (aapi/find-node root #(= '(f x) (aapi/node-form %))))))
      (assert-typed-call-metadata-only (aapi/find-node root #(= '(f x) (aapi/node-form %))))))
  (testing "local fn invocation keeps callable metadata with outer local"
    (let [root (atst/analyze-form '(let [f (fn [x] nil)]
                                     (skeptic.test-examples.basics/int-add 1 (f x)))
                                   (atst/locals 'x))]
      (is (= (atst/T s/Int) (aapi/node-type root)))
      (is (= (atst/T (s/eq nil))
             (aapi/node-type (aapi/find-node root #(= '(f x) (aapi/node-form %))))))
      (assert-typed-call-metadata-only (aapi/find-node root #(= '(f x) (aapi/node-form %)))))))
