(ns skeptic.analysis.calls-test
  (:require [clojure.test :refer [deftest is testing]]
            [schema.core :as s]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.schema.cast-support :as ascs]
            [skeptic.analysis.schema-base :as sb]
            [skeptic.analysis-test :as atst]
            [skeptic.checking :as checking]
            [skeptic.schematize :as schematize]
            [skeptic.static-call-examples]
            [skeptic.test-examples :as test-examples])
  (:import [java.io File]))

(def static-call-examples-file (File. "src/skeptic/static_call_examples.clj"))

(def static-call-dict
  (merge (schematize/ns-schemas {} 'skeptic.static-call-examples)
         {'user {:type (atst/T skeptic.static-call-examples/UserDesc)}
          'counts {:type (atst/T skeptic.static-call-examples/MaybeCount)}
          'left {:type (atst/T skeptic.static-call-examples/LeftFields)}
          'right {:type (atst/T skeptic.static-call-examples/RightFields)}}))

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
                                                            (atst/local-schemas {'f (s/make-fn-schema s/Int [[]])})))
          nested-invoke (atst/project-ast (atst/analyze-form '((f 1) 3 4)
                                                             (atst/locals 'f)))]
      (is (= :do (:op do-form)))
      (is (= :static-call (:op plus-form)))
      (is (= :invoke (:op local-invoke)))
      (is (= :invoke (:op nested-invoke))))))

(deftest schema-application-schemas-test
  (testing "application schemas"
    (let [dynamic-call (atst/project-ast (atst/analyze-form '(+ 1 2)))
          known-call (atst/project-ast (atst/analyze-form '(skeptic.test-examples/int-add 1 2)))]
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:actual-argtypes dynamic-call)))
      (is (= (atst/T s/Any) (:type dynamic-call)))
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:actual-argtypes known-call)))
      (is (= [(atst/T s/Int) (atst/T s/Int)] (:expected-argtypes known-call)))
      (is (= (atst/T s/Int) (:type known-call))))))

(deftest analyse-application-test
  (testing "original partially unknown application setup"
    (let [root (atst/project-ast (atst/analyze-form '(+ 1 x)))]
      (is (= :static-call (:op root)))
      (is (= [s/Int s/Any] (:actual-arglist root)))))
  (testing "original zero-arity application setup"
    (let [root (atst/project-ast (atst/analyze-form '(f)))]
      (is (= :invoke (:op root)))
      (is (= 0 (count (:actual-arglist root))))))
  (testing "original nested application setup"
    (let [root (atst/project-ast (atst/analyze-form '((f 1) 3 4)))]
      (is (= :invoke (:op root)))
      (is (= 2 (count (:actual-arglist root))))
      (is (= '(f 1) (:form (atst/child-projection root :fn)))))))

(deftest attach-schema-info-application-test
  (testing "original generic application schema setup"
    (let [root (atst/project-ast (atst/analyze-form {} '(+ 1 2)))]
      (is (= [s/Int s/Int] (:actual-arglist root)))
      (is (= s/Any (:schema root)))))
  (testing "original known application schema setup"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                                    '(skeptic.test-examples/int-add 1 2)))]
      (is (= [s/Int s/Int] (:actual-arglist root)))
      (is (= [s/Int s/Int] (:expected-arglist root)))
      (is (= s/Int (:schema root))))))

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
    (is (= s/Symbol (:schema symbol-call)))
    (is (= [s/Str] (:expected-arglist symbol-call)))
    (is (= s/Keyword (:schema keyword-call)))
    (is (= s/Int (:schema int-call)))
    (is (= s/Symbol (:schema quoted-symbol)))))

(deftest static-call-analysis-test
  (testing "get returns field schemas from typed maps"
    (let [required-get (atst/project-ast (atst/analyze-form static-call-dict
                                                            '(get user :name)
                                                            {:ns 'skeptic.analysis-test
                                                             :locals {'user skeptic.static-call-examples/UserDesc}}))
          optional-get (atst/project-ast (atst/analyze-form static-call-dict
                                                            '(get user :nickname)
                                                            {:ns 'skeptic.analysis-test
                                                             :locals {'user skeptic.static-call-examples/UserDesc}}))
          defaulted-get (atst/project-ast (atst/analyze-form static-call-dict
                                                             '(get counts :count "zero")
                                                             {:ns 'skeptic.analysis-test
                                                              :locals {'counts skeptic.static-call-examples/MaybeCount}}))]
      (is (= s/Str (:schema required-get)))
      (is (= (s/maybe s/Str) (:schema optional-get)))
      (is (ascs/schema-equivalent? (sb/join s/Int s/Str)
                                    (:schema defaulted-get)))))

  (testing "merge returns merged map schemas"
    (let [merged (atst/project-ast (atst/analyze-form static-call-dict
                                                      '(merge left right)
                                                      {:ns 'skeptic.analysis-test
                                                       :locals {'left skeptic.static-call-examples/LeftFields
                                                                'right skeptic.static-call-examples/RightFields}}))]
      (is (= {:a s/Int :b s/Int}
             (:schema merged)))))

  (testing "rebuilt maps stay in semantic map format"
    (let [root (atst/project-ast (atst/analyze-form static-call-dict
                                                      '{:name (get user :name)
                                                        :nickname (get user :nickname)}
                                                      {:ns 'skeptic.analysis-test
                                                       :locals {'user skeptic.static-call-examples/UserDesc}}))]
      (is (= {:name s/Str
              :nickname (s/maybe s/Str)}
             (:schema root))))))

(deftest resolved-static-get-feeds-parent-call-test
  (testing "resolved static get feeds final reduced schemas into parent calls"
    (let [dict (schematize/ns-schemas {} 'skeptic.static-call-examples)
          {:keys [resolved]} (checking/analyze-source-exprs dict
                                                            'skeptic.static-call-examples
                                                            static-call-examples-file
                                                            (atst/source-exprs-in 'skeptic.static-call-examples static-call-examples-file))
          failure-ast (atst/ast-by-name resolved 'nested-multi-step-failure)
          call-node (atst/node-by-form failure-ast '(nested-multi-step-takes-str (get (nested-multi-step-g) :value)))]
      (is (= [(atst/T s/Int)] (:actual-argtypes call-node))))))

(deftest attach-schema-info-local-fn-invocation-test
  (testing "local fn invocation through int-add"
    (let [root (atst/project-ast (atst/analyze-form test-examples/sample-dict
                                                    '(let [f (fn [x] nil)]
                                                       (skeptic.test-examples/int-add 1 (f x)))))]
      (is (= s/Int (:schema root)))
      (is (= (s/maybe s/Any)
             (:schema (atst/find-projected-node root #(= '(f x) (:form %))))))))
  (testing "local fn invocation keeps callable metadata with outer local"
    (let [root (atst/project-ast (atst/analyze-form '(let [f (fn [x] nil)]
                                                       (skeptic.test-examples/int-add 1 (f x)))
                                                     (atst/locals 'x)))]
      (is (= s/Int (:schema root)))
      (is (= (s/maybe s/Any)
             (:schema (atst/find-projected-node root #(= '(f x) (:form %)))))))))
