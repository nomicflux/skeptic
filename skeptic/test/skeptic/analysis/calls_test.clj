(ns skeptic.analysis.calls-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis-test :as atst]
            [skeptic.checking.pipeline :as checking]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.static-call-examples]
            [skeptic.test-examples :as test-examples])
  (:import [java.io File]))

(def static-call-examples-file (File. "src/skeptic/static_call_examples.clj"))

(def static-call-dict
  (merge (typed-decls/typed-ns-entries {} 'skeptic.static-call-examples)
         {'user {:type (atst/T skeptic.static-call-examples/UserDesc)}
          'counts {:type (atst/T skeptic.static-call-examples/MaybeCount)}
          'left {:type (atst/T skeptic.static-call-examples/LeftFields)}
          'right {:type (atst/T skeptic.static-call-examples/RightFields)}}))

(defn assert-typed-call-metadata-only
  [node]
  (is (contains? node :type))
  (is (contains? node :actual-argtypes))
  (is (or (contains? node :expected-argtypes)
          (contains? node :output-type)
          (contains? node :type)))
  (is (not (contains? node :schema)))
  (is (not (contains? node :output)))
  (is (not (contains? node :expected-arglist)))
  (is (not (contains? node :actual-arglist))))

(deftest calls-predicate-and-qualify-unit-test
  (testing "qualify-symbol"
    (is (nil? (ac/qualify-symbol nil nil)))
    (is (= 'already/qualified (ac/qualify-symbol 'foo 'already/qualified)))
    (is (= 'my.ns/x (ac/qualify-symbol 'my.ns 'x)))
    (is (= 'x (ac/qualify-symbol nil 'x))))
  (testing "call-shape predicates on synthetic fn nodes"
    (is (ac/merge-call? {:form 'merge}))
    (is (ac/contains-call? {:form 'contains?}))
    (is (ac/get-call? {:form 'get}))
    (is (ac/static-get-call? {:class clojure.lang.RT :method 'get}))
    (is (ac/static-merge-call? {:class clojure.lang.RT :method 'merge}))
    (is (ac/static-contains-call? {:class clojure.lang.RT :method 'contains?}))))

(deftest invoke-and-static-application-roots-test
  (testing "do, static-call, and invoke roots"
    (let [do-form (atst/project-ast (atst/analyze-form '(do (str "hello") (+ 1 2))))
          plus-form (atst/project-ast (atst/analyze-form '(+ 1 x) (atst/locals 'x)))
          local-invoke (atst/project-ast (atst/analyze-form '(f)
                                                            (atst/local-types {'f {:type (atst/T (s/make-fn-schema s/Int [[]]))
                                                                                   :output-type (atst/T s/Int)
                                                                                   :arglists {0 {:arglist []
                                                                                                 :count 0
                                                                                                 :types []}}}})))
          nested-invoke (atst/project-ast (atst/analyze-form '((f 1) 3 4)
                                                             (atst/locals 'f)))]
      (is (= :do (:op do-form)))
      (is (= :static-call (:op plus-form)))
      (is (= :invoke (:op local-invoke)))
      (is (= :invoke (:op nested-invoke))))))

(deftest typed-application-call-test
  (testing "application types"
    (let [dynamic-call (atst/project-ast (atst/analyze-form '(+ 1 2)))
          unknown-invoke (atst/project-ast (atst/analyze-form '(f 1 2)
                                                              (atst/locals 'f)))
          known-call (atst/project-ast (atst/analyze-form '(skeptic.test-examples/int-add 1 2)))]
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:actual-argtypes dynamic-call)))
      (is (= atst/num-ground (:type dynamic-call)))
      (assert-typed-call-metadata-only dynamic-call)
      (is (= (atst/T (s/make-fn-schema s/Any [[s/Any s/Any]]))
             (:fn-type unknown-invoke)))
      (assert-typed-call-metadata-only unknown-invoke)
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:actual-argtypes known-call)))
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:expected-argtypes known-call)))
      (is (= (atst/T s/Int) (:type known-call)))
      (assert-typed-call-metadata-only known-call))))

(deftest analyse-application-test
  (testing "original partially unknown application setup"
    (let [root (atst/project-ast (atst/analyze-form '(+ 1 x)))]
      (is (= :static-call (:op root)))
      (is (= [(atst/T s/Int) (atst/T s/Any)] (:actual-argtypes root)))))
  (testing "original zero-arity application setup"
    (let [root (atst/project-ast (atst/analyze-form '(f)))]
      (is (= :invoke (:op root)))
      (is (= 0 (count (:actual-argtypes root))))
      (assert-typed-call-metadata-only root)))
  (testing "original nested application setup"
    (let [root (atst/project-ast (atst/analyze-form '((f 1) 3 4)))]
      (is (= :invoke (:op root)))
      (is (= 2 (count (:actual-argtypes root))))
      (is (= '(f 1) (:form (atst/child-projection root :fn))))
      (assert-typed-call-metadata-only root))))

(deftest attach-type-info-application-test
  (testing "original generic application typed setup"
    (let [root (atst/project-ast (atst/analyze-form {} '(+ 1 2)))]
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:actual-argtypes root)))
      (is (= atst/num-ground (:type root)))
      (assert-typed-call-metadata-only root)))
  (testing "original known application typed setup"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                                    '(skeptic.test-examples/int-add 1 2)))]
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:actual-argtypes root)))
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:expected-argtypes root)))
      (is (= (atst/T s/Int) (:type root)))
      (assert-typed-call-metadata-only root))))

(deftest canonicalized-callable-entry-test
  (let [raw-symbol-entry (typed-decls/desc->typed-entry
                          {:name "f"
                           :schema (s/make-fn-schema clojure.lang.Symbol
                                                     [[(s/one java.lang.String 'arg)]])
                           :output clojure.lang.Symbol
                           :arglists {1 {:arglist ['arg]
                                         :count 1
                                         :schema [{:schema java.lang.String
                                                   :optional? false
                                                   :name 'arg}]}}})
        raw-keyword-entry (typed-decls/desc->typed-entry
                           {:name "f"
                            :schema (s/make-fn-schema clojure.lang.Keyword
                                                      [[(s/one s/Any 'arg)]])
                            :output clojure.lang.Keyword
                            :arglists {1 {:arglist ['arg]
                                          :count 1
                                          :schema [{:schema s/Any
                                                    :optional? false
                                                    :name 'arg}]}}})
        raw-int-entry (typed-decls/desc->typed-entry
                       {:name "f"
                        :schema (s/make-fn-schema java.lang.Integer
                                                  [[(s/one s/Any 'arg)]])
                        :output java.lang.Integer
                        :arglists {1 {:arglist ['arg]
                                      :count 1
                                      :schema [{:schema s/Any
                                                :optional? false
                                                :name 'arg}]}}})
        symbol-call (atst/project-ast (atst/analyze-form {}
                                                        '(f "x")
                                                        {:ns 'skeptic.analysis-test
                                                         :locals {'f raw-symbol-entry}}))
        keyword-call (atst/project-ast (atst/analyze-form {}
                                                         '(f :x)
                                                         {:ns 'skeptic.analysis-test
                                                          :locals {'f raw-keyword-entry}}))
        int-call (atst/project-ast (atst/analyze-form {}
                                                     '(f :x)
                                                     {:ns 'skeptic.analysis-test
                                                      :locals {'f raw-int-entry}}))
        quoted-symbol (atst/project-ast (atst/analyze-form '(quote foo)))]
    (is (= (atst/T s/Symbol) (:type symbol-call)))
    (is (= [(atst/T s/Str)] (:expected-argtypes symbol-call)))
    (is (= (atst/T s/Keyword) (:type keyword-call)))
    (is (= (atst/T s/Int) (:type int-call)))
    (is (= (atst/T s/Symbol) (:type quoted-symbol)))
    (assert-typed-call-metadata-only symbol-call)
    (assert-typed-call-metadata-only keyword-call)
    (assert-typed-call-metadata-only int-call)))

(deftest static-call-analysis-test
  (testing "get returns declared field types from typed maps"
      (let [required-get (atst/project-ast (atst/analyze-form static-call-dict
                                                            '(get user :name)
                                                            {:ns 'skeptic.analysis-test
                                                             :locals {'user {:type (atst/T skeptic.static-call-examples/UserDesc)}}}))
          optional-get (atst/project-ast (atst/analyze-form static-call-dict
                                                            '(get user :nickname)
                                                            {:ns 'skeptic.analysis-test
                                                             :locals {'user {:type (atst/T skeptic.static-call-examples/UserDesc)}}}))
          defaulted-get (atst/project-ast (atst/analyze-form static-call-dict
                                                             '(get counts :count "zero")
                                                             {:ns 'skeptic.analysis-test
                                                              :locals {'counts {:type (atst/T skeptic.static-call-examples/MaybeCount)}}}))]
      (is (= (atst/T s/Str) (:type required-get)))
      (is (= (atst/T (s/maybe s/Str)) (:type optional-get)))
      (is (= (atst/T (sb/join s/Int s/Str))
             (:type defaulted-get)))))

  (testing "merge returns merged typed maps"
    (let [merged (atst/project-ast (atst/analyze-form static-call-dict
                                                      '(merge left right)
                                                      {:ns 'skeptic.analysis-test
                                                       :locals {'left {:type (atst/T skeptic.static-call-examples/LeftFields)}
                                                                'right {:type (atst/T skeptic.static-call-examples/RightFields)}}}))]
      (is (= (atst/T {:a s/Int :b s/Int})
             (:type merged)))))

  (testing "rebuilt maps stay in semantic map format"
    (let [root (atst/project-ast (atst/analyze-form static-call-dict
                                                      '{:name (get user :name)
                                                        :nickname (get user :nickname)}
                                                      {:ns 'skeptic.analysis-test
                                                       :locals {'user {:type (atst/T skeptic.static-call-examples/UserDesc)}}}))]
      (is (= (atst/T {:name s/Str
                      :nickname (s/maybe s/Str)})
             (:type root))))))

(deftest resolved-static-get-feeds-parent-call-test
  (testing "resolved static get feeds final reduced field types into parent calls"
    (let [dict (typed-decls/typed-ns-entries {} 'skeptic.static-call-examples)
          {:keys [resolved]} (checking/analyze-source-exprs dict
                                                            'skeptic.static-call-examples
                                                            static-call-examples-file
                                                            (atst/source-exprs-in 'skeptic.static-call-examples static-call-examples-file))
          failure-ast (atst/ast-by-name resolved 'nested-multi-step-failure)
          call-node (atst/node-by-form failure-ast '(nested-multi-step-takes-str (get (nested-multi-step-g) :value)))]
      (is (= [(atst/T s/Int)] (:actual-argtypes call-node)))
      (assert-typed-call-metadata-only call-node))))

(deftest attach-type-info-local-fn-invocation-test
  (testing "local fn invocation through int-add"
    (let [root (atst/project-ast (atst/analyze-form atst/typed-test-examples-dict
                                                    '(let [f (fn [x] nil)]
                                                       (skeptic.test-examples/int-add 1 (f x)))))]
      (is (= (atst/T s/Int) (:type root)))
      (is (= (atst/T (s/maybe s/Any))
             (:type (atst/find-projected-node root #(= '(f x) (:form %))))))
      (assert-typed-call-metadata-only (atst/find-projected-node root #(= '(f x) (:form %))))))
  (testing "local fn invocation keeps callable metadata with outer local"
    (let [root (atst/project-ast (atst/analyze-form '(let [f (fn [x] nil)]
                                                       (skeptic.test-examples/int-add 1 (f x)))
                                                     (atst/locals 'x)))]
      (is (= (atst/T s/Int) (:type root)))
      (is (= (atst/T (s/maybe s/Any))
             (:type (atst/find-projected-node root #(= '(f x) (:form %))))))
      (assert-typed-call-metadata-only (atst/find-projected-node root #(= '(f x) (:form %)))))))
