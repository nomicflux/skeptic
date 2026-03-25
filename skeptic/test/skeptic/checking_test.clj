(ns skeptic.checking-test
  (:require [clojure.test :refer [are deftest is]]
            [schema.core :as s]
            [skeptic.analysis.schema :as as]
            [skeptic.checking :as sut]
            [skeptic.counterexamples]
            [skeptic.inconsistence :as inconsistence]
            [skeptic.schematize :as schematize]
            [skeptic.test-examples]
            [skeptic.utils])
  (:import [java.io File]))

(defmacro in-test-examples
  [& body]
  `(sut/block-in-ns 'skeptic.test-examples (File. "test/skeptic/test_examples.clj")
                    ~@body))

(def test-file (File. "test/skeptic/test_examples.clj"))
(def counterexamples-file (File. "src/skeptic/counterexamples.clj"))
(def schematize-file (File. "src/skeptic/schematize.clj"))
(def utils-file (File. "src/skeptic/utils.clj"))

(def test-dict (in-test-examples (schematize/ns-schemas {} 'skeptic.test-examples)))
(def counterexamples-dict (schematize/ns-schemas {} 'skeptic.counterexamples))
(def schematize-dict (schematize/ns-schemas {} 'skeptic.schematize))
(def utils-dict (schematize/ns-schemas {} 'skeptic.utils))

(let [fn-map (atom {})]
  (s/defn normalize-fn-code
    [opts f]
    (get (swap! fn-map update f (fn [x]
                                  (or x (->> f
                                             (schematize/get-fn-code opts)
                                             read-string))))
         f)))

(s/defn check-fn
  ([dict f]
   (check-fn dict f {}))
  ([dict f opts]
   (sut/check-s-expr dict
                     (normalize-fn-code opts f)
                     (assoc opts :ns 'skeptic.test-examples))))

(defn result-errors
  [results]
  (mapcat (juxt :blame :errors) results))

(defn result-pairs
  [results]
  (set (map (juxt :blame :errors) results)))

(deftest resolution-path-resolutions
  (in-test-examples
   (let [results (check-fn test-dict 'skeptic.test-examples/sample-let-bad-fn {:keep-empty true})
         call-result (first (filter #(= '(int-add x y z) (:blame %)) results))
         local-vars (get-in call-result [:context :local-vars])]
     (is (some? call-result))
     (is (= [] (:errors call-result)))
     (is (= s/Any (get-in local-vars ['x :schema])))
     (is (= [] (get-in local-vars ['x :resolution-path])))
     (is (= s/Int (get-in local-vars ['y :schema])))
     (is (= ['(int-add 1 nil) 'int-add]
            (mapv :form (get-in local-vars ['y :resolution-path]))))
     (is (= s/Int (-> local-vars (get 'y) :resolution-path first :schema)))
     (is (= s/Int (get-in local-vars ['z :schema])))
     (is (= ['(int-add 2 3) 'int-add]
            (mapv :form (get-in local-vars ['z :resolution-path]))))
     (is (= s/Int (-> local-vars (get 'z) :resolution-path first :schema)))
     (is (= ['int-add]
            (mapv :form (get-in call-result [:context :refs]))))
     (is (every? some? (mapv :schema (get-in call-result [:context :refs])))))))

(deftest working-functions
  (in-test-examples
   (are [f] (try (let [res (check-fn test-dict f)]
                   (cond
                     (empty? res) true
                     :else (do (println "Failed for" f "\n\tfor reasons" res) false)))
                 (catch Exception e
                   (throw (ex-info "Exception checking function"
                                   {:function f
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

(deftest new-failing-function
  (in-test-examples
   (are [f errors] (= (set (partition 2 errors))
                      (result-pairs (check-fn test-dict f)))
     'skeptic.test-examples/sample-bad-schema-fn ['(int-add not-an-int 2)
                                                  [(inconsistence/mismatched-ground-type-msg {:expr '(int-add not-an-int 2)
                                                                                              :arg 'not-an-int}
                                                                                             s/Str
                                                                                             s/Int)]])))

(deftest failing-functions
  (in-test-examples
   (are [f errors] (= (set (partition 2 errors))
                      (result-pairs (check-fn test-dict f)))
     'skeptic.test-examples/sample-bad-fn ['(int-add nil x)
                                           [(inconsistence/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-bad-let-fn ['(int-add x y)
                                               [(inconsistence/mismatched-nullable-msg {:expr '(int-add x y) :arg 'y} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-let-bad-fn ['(int-add 1 nil)
                                               [(inconsistence/mismatched-nullable-msg {:expr '(int-add 1 nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-line-body ['(int-add nil x)
                                                    [(inconsistence/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-line-let-body ['(int-add 1 (f x))
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(int-add 1 (f x)) :arg '(f x)} (s/maybe s/Any) s/Int)]
                                                        '(int-add 2 3 4 nil)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(int-add 2 3 4 nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                        '(int-add nil x)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]
                                                        '(int-add 2 nil)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(int-add 2 nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                        '(int-add w 1 x y z)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(int-add w 1 x y z) :arg 'w} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-mismatched-types ['(int-add x "hi")
                                                     [(inconsistence/mismatched-ground-type-msg {:expr '(int-add x "hi") :arg "hi"} s/Str s/Int)]]
     'skeptic.test-examples/sample-let-mismatched-types ['(int-add x s)
                                                         [(inconsistence/mismatched-ground-type-msg {:expr '(int-add x s) :arg 's} s/Str s/Int)]]
     'skeptic.test-examples/sample-let-fn-bad1-fn ['(int-add y nil)
                                                   [(inconsistence/mismatched-nullable-msg {:expr '(int-add y nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-arity-fn ['(int-add x y z nil)
                                                   [(inconsistence/mismatched-nullable-msg {:expr '(int-add x y z nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                   '(int-add x y nil)
                                                   [(inconsistence/mismatched-nullable-msg {:expr '(int-add x y nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                   '(int-add x nil)
                                                   [(inconsistence/mismatched-nullable-msg {:expr '(int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-metadata-fn ['(int-add x nil)
                                                [(inconsistence/mismatched-nullable-msg {:expr '(int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-doc-fn ['(int-add x nil)
                                           [(inconsistence/mismatched-nullable-msg {:expr '(int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-doc-and-metadata-fn ['(int-add x nil)
                                                        [(inconsistence/mismatched-nullable-msg {:expr '(int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-fn-once ['(int-add y nil)
                                            [(inconsistence/mismatched-nullable-msg {:expr '(int-add y nil) :arg nil} (s/maybe s/Any) s/Int)]])))

(deftest check-ns-uses-raw-forms
  (in-test-examples
   (let [results (vec (sut/check-ns test-dict 'skeptic.test-examples test-file {:remove-context true}))]
     (is (seq results))
     (is (some #(= '(int-add x "hi") (:blame %)) results))
     (is (not-any? #(and (seq? (:blame %))
                         (= "schema.core" (namespace (first (:blame %)))))
                   results)))))

(deftest check-ns-allows-empty-namespaces
  (require 'skeptic.core-fns)
  (is (= []
         (vec (sut/check-ns (schematize/ns-schemas {} 'skeptic.core-fns)
                            'skeptic.core-fns
                            (File. "src/skeptic/core_fns.clj")
                            {})))))

(deftest check-ns-reads-auto-resolved-keywords-in-target-ns
  (require 'skeptic.test-examples)
  (let [results (vec (sut/check-ns (schematize/ns-schemas {} 'skeptic.test-examples)
                                   'skeptic.test-examples
                                   test-file
                                   {:keep-empty true
                                    :remove-context true}))]
    (is (seq results))
    (is (some #(= "(int-add x (::s/key2 y))" (:source-expression %)) results))
    (is (= {:blame '(int-add x (:schema.core/key2 y))
            :source-expression "(int-add x (::s/key2 y))"
            :expanded-expression '(int-add x (:schema.core/key2 y))
            :enclosing-form 'skeptic.test-examples/sample-namespaced-keyword-fn
            :focuses []}
           (some #(when (= "(int-add x (::s/key2 y))" (:source-expression %))
                    (dissoc (select-keys % [:blame :source-expression :expanded-expression :location :enclosing-form :focuses])
                            :location))
                 results)))
    (is (= {:file "test/skeptic/test_examples.clj"
            :line 52
            :column 5}
           (some #(when (= "(int-add x (::s/key2 y))" (:source-expression %))
                    (select-keys (:location %) [:file :line :column]))
                 results)))))

(deftest call-mismatch-reports-affected-input-and-location
  (let [results (vec (sut/check-ns test-dict
                                   'skeptic.test-examples
                                   test-file
                                   {:remove-context true}))
        result (some #(when (= '(int-add x y) (:blame %)) %) results)]
    (is (= ['y] (:focuses result)))
    (is (= ["y"] (:focus-sources result)))
    (is (= "(int-add x y)" (:source-expression result)))
    (is (= {:file "test/skeptic/test_examples.clj"
            :line 77
            :column 5}
           (select-keys (:location result) [:file :line :column])))
    (is (= 'skeptic.test-examples/sample-bad-let-fn
           (:enclosing-form result)))))

(deftest schema-wrapper-regression
  (in-test-examples
   (is (= ['(int-add nil x)
           [(inconsistence/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
          (result-errors (check-fn test-dict 'skeptic.test-examples/sample-schema-bad-fn))))))

(deftest symbol-output-schema-regression
  (let [form (->> 'skeptic.schematize/fully-qualify-str
                  (schematize/get-fn-code {})
                  read-string)
        results (vec (sut/check-s-expr schematize-dict
                                       form
                                       {:ns 'skeptic.schematize
                                        :source-file schematize-file
                                        :remove-context true}))]
    (is (= [] results))))

(deftest collect-schemas-output-schema-regression
  (let [form (->> 'skeptic.schematize/collect-schemas
                  (schematize/get-fn-code {})
                  read-string)
        results (vec (sut/check-s-expr schematize-dict
                                       form
                                       {:ns 'skeptic.schematize
                                        :source-file schematize-file
                                        :remove-context true}))]
    (is (= [] results))))

(deftest counterexamples-report-callsite-and-output-errors
  (let [results (vec (sut/check-ns counterexamples-dict
                                   'skeptic.counterexamples
                                   counterexamples-file
                                   {:remove-context true}))]
    (is (= #{['(str "not-an-int:" name)
              [(inconsistence/mismatched-output-schema-msg {:expr 'claimed-int-but-string
                                                            :arg '(str "not-an-int:" name)}
                                                           s/Str
                                                           s/Int)]]
             ['(if flag 42 "forty-two")
              [(inconsistence/mismatched-output-schema-msg {:expr 'claimed-int-with-string-branch
                                                            :arg '(if flag 42 "forty-two")}
                                                           (as/join s/Int s/Str)
                                                           s/Int)]]
             ['(int-add base (maybe-bonus eligible?))
              [(inconsistence/mismatched-nullable-msg {:expr '(int-add base (maybe-bonus eligible?))
                                                       :arg '(maybe-bonus eligible?)}
                                                      (s/maybe s/Any)
                                                      s/Int)]]
             ['(int-add base (maybe-penalty missing-payment?))
              [(inconsistence/mismatched-nullable-msg {:expr '(int-add base (maybe-penalty missing-payment?))
                                                       :arg '(maybe-penalty missing-payment?)}
                                                      (s/maybe s/Any)
                                                      s/Int)]]}
           (result-pairs results)))
    (is (= #{['(str "not-an-int:" name)
              [(inconsistence/mismatched-output-schema-msg {:expr 'claimed-int-but-string
                                                            :arg '(str "not-an-int:" name)}
                                                           s/Str
                                                           s/Int)]]
             ['(if flag 42 "forty-two")
              [(inconsistence/mismatched-output-schema-msg {:expr 'claimed-int-with-string-branch
                                                            :arg '(if flag 42 "forty-two")}
                                                           (as/join s/Int s/Str)
                                                           s/Int)]]}
           (result-pairs
            (vec (mapcat #(sut/check-s-expr counterexamples-dict
                                            %
                                            {:ns 'skeptic.counterexamples
                                             :remove-context true})
                         ['(s/defn claimed-int-but-string :- schema.core/Int
                            [name :- schema.core/Str]
                            (str "not-an-int:" name))
                          '(s/defn claimed-int-with-string-branch :- schema.core/Int
                            [flag :- schema.core/Bool]
                            (if flag
                              42
                              "forty-two"))])))))))

(deftest output-mismatch-reports-source-body-and-expanded-expression
  (let [results (vec (sut/check-ns utils-dict
                                   'skeptic.utils
                                   utils-file
                                   {:remove-context true}))
        result (some #(when (= 'skeptic.utils/combine-descs (:enclosing-form %)) %) results)]
    (is (= '{:name (or n1 n2)
             :schema (or s1 s2)
             :output (or o1 o2)
             :arglists (merge a1 a2)}
           (:blame result)))
    (is (= "{:name (or n1 n2)\n   :schema (or s1 s2)\n   :output (or o1 o2)\n   :arglists (merge a1 a2)}"
           (:source-expression result)))
    (is (= 'let* (first (:expanded-expression result))))
    (is (= {:file "src/skeptic/utils.clj"
            :line 23
            :column 3}
           (select-keys (:location result) [:file :line :column])))
    (is (= 'skeptic.utils/combine-descs
           (:enclosing-form result)))))
