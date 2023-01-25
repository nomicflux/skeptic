(ns skeptic.checking-test
  (:require [skeptic.checking :as sut]
            [skeptic.test-examples :as test-examples]
            [clojure.test :refer [deftest is are]]
            [skeptic.schematize :as schematize]
            [skeptic.inconsistence :as inconsistence]
            [schema.core :as s]
            [skeptic.analysis.schema :as analysis-schema])
  (:import [java.io File]))

(defmacro in-test-examples
  [& body]
  `(sut/block-in-ns 'skeptic.test-examples (File. "test/skeptic/test_examples.clj")
                    ~@body))

(def test-dict (in-test-examples (schematize/ns-schemas {} 'skeptic.test-examples)))
(def test-refs (ns-map 'skeptic.test-examples))

(defn manual-check
  ([f]
   (manual-check f {}))
  ([f opts]
   (in-test-examples
    (sut/check-fn test-refs test-dict f opts))))

(defn manual-annotate
  [f]
  (in-test-examples (sut/annotate-fn test-refs test-dict f)))

(deftest resolution-path-resolutions
  (let [refs {20 {:expr 'y, :idx 20, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                                                 'y {:schema s/Int, :resolution-path [{:idx 11}]},
                                                 'z {:schema s/Int, :resolution-path [{:idx 16}]}},
                  :path ['skeptic.test-examples/sample-let-bad-fn],
                  :resolution-path [{:idx 11} {:expr 'y, :schema s/Int}], :schema s/Int},
              15 {:expr 3, :idx 15, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                                                 'y {:schema s/Int, :resolution-path [{:idx 11}]}},
                  :path ['skeptic.test-examples/sample-let-bad-fn 'z], :schema s/Int},
              21 {:expr 'z, :idx 21, :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                                                 'y {:schema s/Int, :resolution-path [{:idx 11}]},
                                                 'z {:schema s/Int, :resolution-path [{:idx 16}]}},
                  :path ['skeptic.test-examples/sample-let-bad-fn],
                  :resolution-path [{:idx 16} {:expr 'z, :schema s/Int}], :schema s/Int},
              13 {:args [14 15], :path ['skeptic.test-examples/sample-let-bad-fn 'z],
                  :schema (s/=> s/Int s/Int s/Int), :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                                                         'y {:schema s/Int, :resolution-path [{:idx 11}]}},
                  :arglist [s/Int s/Int], :output s/Int,
                  :expr 'skeptic.test-examples/int-add, :finished? true, :fn-position? true, :idx 13},
              22 {:path ['skeptic.test-examples/sample-let-bad-fn], :schema s/Int,
                  :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                               'y {:schema s/Int, :resolution-path [{:idx 11}]},
                               'z {:schema s/Int, :resolution-path [{:idx 16}]}},
                  :expr '({:expr 'skeptic.test-examples/int-add, :idx 18} {:expr x, :idx 19}
                          {:expr y, :idx 20} {:expr z, :idx 21}), :finished? true,
                  :expected-arglist [s/Int s/Int s/Int], :idx 22, :actual-arglist [s/Any s/Int s/Int],
                  :resolution-path [{:idx 18}]},
              25 {:expr '({:expr fn*, :idx 3} {:expr ({:expr [{:expr x, :idx 4}], :idx 5}
                                                     {:expr ({:expr let*, :idx 6}
                                                             {:expr [{:expr y, :idx 7}
                                                                     {:expr ({:expr 'skeptic.test-examples/int-add, :idx 8} {:expr 1, :idx 9} {:expr nil, :idx 10}), :idx 11} {:expr z, :idx 12} {:expr ({:expr 'skeptic.test-examples/int-add, :idx 13} {:expr 2, :idx 14} {:expr 3, :idx 15}), :idx 16}], :idx 17}
                                                             {:expr ({:expr 'skeptic.test-examples/int-add, :idx 18} {:expr x, :idx 19}
                                                                                                                                                                                                                                                                                                                             {:expr y, :idx 20} {:expr z, :idx 21}), :idx 22}), :idx 23}), :idx 24}), :idx 25,
                  :local-vars {}, :path ['skeptic.test-examples/sample-let-bad-fn],
                  :output s/Int, :schema (s/=> s/Int s/Any),
                  :arglists {1 {:arglist ['x], :count 1, :schema [{:schema s/Any, :optional? false, :name 'x}]}},
                  :finished? true},
              23 {:expr '({:expr let*, :idx 6} {:expr [{:expr y, :idx 7} {:expr ({:expr 'skeptic.test-examples/int-add, :idx 8} {:expr 1, :idx 9} {:expr nil, :idx 10}), :idx 11} {:expr z, :idx 12} {:expr ({:expr 'skeptic.test-examples/int-add, :idx 13} {:expr 2, :idx 14} {:expr 3, :idx 15}), :idx 16}], :idx 17}
                          {:expr ({:expr 'skeptic.test-examples/int-add, :idx 18} {:expr x, :idx 19} {:expr y, :idx 20} {:expr z, :idx 21}), :idx 22}), :idx 23,
                  :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
                  :path ['skeptic.test-examples/sample-let-bad-fn], :schema s/Int,
                  :resolution-path [{:idx 22}], :finished? true},
              19 {:expr 'x, :idx 19,
                  :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                               'y {:schema s/Int, :resolution-path [{:idx 11}]},
                               'z {:schema s/Int, :resolution-path [{:idx 16}]}},
                  :path ['skeptic.test-examples/sample-let-bad-fn],
                  :resolution-path [{:expr 'x, :schema s/Any}], :schema s/Any},
              11 {:path ['skeptic.test-examples/sample-let-bad-fn], :schema s/Int,
                  :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}, :name 'y,
                  :expr '({:expr 'skeptic.test-examples/int-add, :idx 8} {:expr 1, :idx 9} {:expr nil, :idx 10}),
                  :finished? true, :expected-arglist [s/Int s/Int], :idx 11,
                  :actual-arglist [s/Int (s/maybe s/Any)], :resolution-path [{:idx 8}]},
              9 {:expr 1, :idx 9,
                 :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
                 :path ['skeptic.test-examples/sample-let-bad-fn 'y], :schema s/Int},
              14 {:expr 2, :idx 14,
                  :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                               'y {:schema s/Int, :resolution-path [{:idx 11}]}},
                  :path ['skeptic.test-examples/sample-let-bad-fn 'z], :schema s/Int},
              26 {:expr '({:expr def, :idx 1} {:expr 'skeptic.test-examples/sample-let-bad-fn, :idx 2}
                          {:expr ({:expr fn*, :idx 3} {:expr ({:expr [{:expr x, :idx 4}], :idx 5}
                                                              {:expr ({:expr let*, :idx 6} {:expr [{:expr y, :idx 7} {:expr ({:expr 'skeptic.test-examples/int-add, :idx 8} {:expr 1, :idx 9} {:expr nil, :idx 10}), :idx 11} {:expr z, :idx 12} {:expr ({:expr 'skeptic.test-examples/int-add, :idx 13} {:expr 2, :idx 14} {:expr 3, :idx 15}), :idx 16}], :idx 17}
                                                                      {:expr ({:expr 'skeptic.test-examples/int-add, :idx 18} {:expr x, :idx 19} {:expr y, :idx 20} {:expr z, :idx 21}), :idx 22}), :idx 23}), :idx 24}), :idx 25}),
                  :idx 26, :name 'skeptic.test-examples/sample-let-bad-fn, :path ['skeptic.test-examples/sample-let-bad-fn],
                  :schema (analysis-schema/variable (s/=> s/Int s/Any)), :resolution-path [{:idx 25}], :finished? true},
              16 {:path ['skeptic.test-examples/sample-let-bad-fn], :schema s/Int,
                  :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                               'y {:schema s/Int, :resolution-path [{:idx 11}]}},
                  :name 'z, :expr '({:expr 'skeptic.test-examples/int-add, :idx 13} {:expr 2, :idx 14} {:expr 3, :idx 15}),
                  :finished? true, :expected-arglist [s/Int s/Int], :idx 16, :actual-arglist [s/Int s/Int], :resolution-path [{:idx 13}]},
              10 {:expr nil, :idx 10,
                  :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}},
                  :path ['skeptic.test-examples/sample-let-bad-fn 'y], :schema (s/maybe s/Any)},
              18 {:args [19 20 21], :path ['skeptic.test-examples/sample-let-bad-fn], :schema (s/=> s/Int s/Int s/Int s/Int),
                  :local-vars {'x {:expr 'x, :name 'x, :schema s/Any},
                               'y {:schema s/Int, :resolution-path [{:idx 11}]},
                               'z {:schema s/Int, :resolution-path [{:idx 16}]}},
                  :arglist [s/Int s/Int s/Int], :output s/Int, :expr 'skeptic.test-examples/int-add,
                  :finished? true, :fn-position? true, :idx 18},
              8 {:args [9 10], :path ['skeptic.test-examples/sample-let-bad-fn 'y], :schema (s/=> s/Int s/Int s/Int),
                 :local-vars {'x {:expr 'x, :name 'x, :schema s/Any}}, :arglist [s/Int s/Int], :output s/Int,
                 :expr 'skeptic.test-examples/int-add, :finished? true, :fn-position? true, :idx 8}}]
    (is (= {'x {:expr 'x, :name 'x, :schema s/Any, :resolution-path []},
            'y {:schema s/Int,
                :resolution-path
                [{:idx 11,
                  :expr
                  '({:expr 'skeptic.test-examples/int-add, :idx 8}
                    {:expr 1, :idx 9}
                    {:expr nil, :idx 10}),
                  :schema s/Int}
                 {:idx 8,
                  :expr 'skeptic.test-examples/int-add,
                  :schema (s/=> s/Int s/Int s/Int)}]},
            'z {:schema s/Int,
                :resolution-path
                [{:idx 16,
                  :expr
                  '({:expr 'skeptic.test-examples/int-add, :idx 13}
                   {:expr 2, :idx 14}
                   {:expr 3, :idx 15}),
                  :schema s/Int}
                 {:idx 13,
                  :expr 'skeptic.test-examples/int-add,
                  :schema (s/=> s/Int s/Int s/Int)}]}}
           (sut/match-up-resolution-paths refs
                                          {'x {:expr 'x, :name 'x, :schema s/Any},
                                           'y {:schema s/Int, :resolution-path [{:idx 11}]},
                                           'z {:schema s/Int, :resolution-path [{:idx 16}]}})))
    (is (= [{:idx 20, :expr 'y, :schema s/Int}
            {:idx 11,
             :expr
             '({:expr 'skeptic.test-examples/int-add, :idx 8}
               {:expr 1, :idx 9}
               {:expr nil, :idx 10}),
             :schema s/Int}
            {:idx 8,
             :expr 'skeptic.test-examples/int-add,
             :schema (s/=> s/Int s/Int s/Int)}]
           ((sut/lookup-resolutions refs) [{:idx 20}])))))

(deftest working-functions
  (in-test-examples
   (are [f] (try (cond
                   (empty? (sut/check-fn test-refs test-dict f)) true
                   :else (do (println "Failed for" f "\n\tfor reasons" (sut/check-fn test-refs test-dict f)) false))
                 (catch Exception e
                   (throw (ex-info "Exception checking function"
                                   {:function f
                                    :test-refs test-refs
                                    :test-dict test-dict
                                    :error e}))))
     'skeptic.test-examples/sample-fn
     'skeptic.test-examples/sample-schema-fn
     'skeptic.test-examples/sample-half-schema-fn
     'skeptic.test-examples/sample-let-fn
     'skeptic.test-examples/sample-if-fn
     'skeptic.test-examples/sample-if-mixed-fn
     'skeptic.test-examples/sample-do-fn
     'skeptic.test-examples/sample-try-catch-fn
     'skeptic.test-examples/sample-try-finally-fn
     'skeptic.test-examples/sample-try-catch-finally-fn
     'skeptic.test-examples/sample-throw-fn
     'skeptic.test-examples/sample-fn-fn
     'skeptic.test-examples/sample-var-fn-fn
     'skeptic.test-examples/sample-found-var-fn-fn
     'skeptic.test-examples/sample-missing-var-fn-fn
     'skeptic.test-examples/sample-namespaced-keyword-fn
     'skeptic.test-examples/sample-let-fn-fn
     'skeptic.test-examples/sample-functional-fn)))

(deftest failing-functions
  (in-test-examples
   (are [f errors] (= errors
                      (mapcat (juxt :blame :errors) (sut/check-fn test-refs test-dict f)))
     'skeptic.test-examples/sample-bad-fn ['(skeptic.test-examples/int-add nil x)
                                           [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-bad-let-fn ['(skeptic.test-examples/int-add x y)
                                               [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add x y):arg 'y} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-let-bad-fn ['(skeptic.test-examples/int-add 1 nil)
                                               [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add 1 nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-line-body ['(skeptic.test-examples/int-add nil x)
                                                    [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-line-let-body ['(skeptic.test-examples/int-add nil x)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]
                                                        '(skeptic.test-examples/int-add 1 (f x))
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add 1 (f x)) :arg '(f x)} (s/maybe s/Any) s/Int)]
                                                        '(skeptic.test-examples/int-add 2 3 4 nil)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add 2 3 4 nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                        '(skeptic.test-examples/int-add w 1 x y z)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add w 1 x y z) :arg 'w} (s/maybe s/Any) s/Int)]
                                                        '(skeptic.test-examples/int-add 2 nil)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add 2 nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-mismatched-types ['(skeptic.test-examples/int-add x "hi")
                                                     [(inconsistence/mismatched-ground-type-msg {:expr '(skeptic.test-examples/int-add x "hi") :arg "hi"} s/Str s/Int)]]
     'skeptic.test-examples/sample-let-mismatched-types ['(skeptic.test-examples/int-add x s)
                                                         [(inconsistence/mismatched-ground-type-msg {:expr '(skeptic.test-examples/int-add x s):arg 's} s/Str s/Int)]]
     'skeptic.test-examples/sample-let-fn-bad1-fn ['(skeptic.test-examples/int-add y nil)
                                                   [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add y nil) :arg nil} (s/maybe s/Any) s/Int)]]
     ;;'skeptic.test-examples/sample-let-fn-bad2-fn [""]
     'skeptic.test-examples/sample-multi-arity-fn ['(skeptic.test-examples/int-add x y z nil)
                                                   [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add x y z nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                   '(skeptic.test-examples/int-add x y nil)
                                                   [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add x y nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                   '(skeptic.test-examples/int-add x nil)
                                                   [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-metadata-fn ['(skeptic.test-examples/int-add x nil)
                                                [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-doc-fn ['(skeptic.test-examples/int-add x nil)
                                           [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-doc-and-metadata-fn ['(skeptic.test-examples/int-add x nil)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-fn-once ['(skeptic.test-examples/int-add y nil)
                                            [(inconsistence/mismatched-nullable-msg {:expr '(skeptic.test-examples/int-add y nil) :arg nil} (s/maybe s/Any) s/Int)]])))
