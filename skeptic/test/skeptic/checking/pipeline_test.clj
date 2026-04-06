(ns skeptic.checking.pipeline-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is]]
            [schema.core :as s]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.checking.pipeline :as sut]
            [skeptic.core :as core]
            [skeptic.best-effort-examples]
            [skeptic.examples]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.source :as source]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.static-call-examples]
            [skeptic.test-examples]
            [skeptic.utils])
  (:import [java.io File]))

(defmacro in-test-examples
  [& body]
  `(sut/block-in-ns 'skeptic.test-examples (File. "test/skeptic/test_examples.clj")
                    ~@body))

(def test-file (File. "test/skeptic/test_examples.clj"))
(def examples-file (File. "src/skeptic/examples.clj"))
(def schema-collect-file (File. "src/skeptic/schema/collect.clj"))
(def static-call-examples-file (File. "src/skeptic/static_call_examples.clj"))
(def utils-file (File. "src/skeptic/utils.clj"))
(def best-effort-file (File. "test/skeptic/best_effort_examples.clj"))

(def test-dict (in-test-examples (typed-decls/typed-ns-entries {} 'skeptic.test-examples)))
(def examples-dict (typed-decls/typed-ns-entries {} 'skeptic.examples))
(def schema-collect-dict (typed-decls/typed-ns-entries {} 'skeptic.schema.collect))
(def static-call-examples-dict (typed-decls/typed-ns-entries {} 'skeptic.static-call-examples))
(def utils-dict (typed-decls/typed-ns-entries {} 'skeptic.utils))

(let [fn-map (atom {})]
  (s/defn normalize-fn-code
    [opts f]
    (get (swap! fn-map update f (fn [x]
                                  (or x (->> f
                                             (source/get-fn-code opts)
                                             read-string))))
         f)))

(s/defn check-fn
  ([dict f]
   (check-fn dict f {}))
  ([dict f opts]
   (sut/check-s-expr dict
                     (normalize-fn-code opts f)
                     (assoc opts
                            :ns 'skeptic.test-examples
                            :source-file test-file))))

(defn result-errors
  [results]
  (mapcat (juxt :blame :errors) results))

(defn result-pairs
  [results]
  (set (map (juxt :blame :errors) results)))

(def ui-internal-markers
  [":skeptic.analysis.types/"
   "placeholder-type"
   "group-type"
   ":ref "
   "source union branch"
   "target union branch"
   "source intersection branch"
   "target intersection branch"])

(defn assert-no-ui-internals
  [text]
  (doseq [marker ui-internal-markers]
    (is (not (str/includes? (str text) marker)))))

(defn strip-ansi
  [text]
  (str/replace (str text) #"\u001B\[[0-9;]*m" ""))

(defn T
  [schema]
  (ab/schema->type schema))

(deftest resolution-path-resolutions
  (in-test-examples
   (let [results (check-fn test-dict 'skeptic.test-examples/sample-let-bad-fn {:keep-empty true})
         call-result (first (filter #(= '(int-add x y z) (:blame %)) results))
         local-vars (get-in call-result [:context :local-vars])]
     (is (some? call-result))
     (is (= [] (:errors call-result)))
     (is (= (T s/Any) (get-in local-vars ['x :type])))
     (is (= [] (get-in local-vars ['x :resolution-path])))
     (is (= (T s/Int) (get-in local-vars ['y :type])))
     (is (= ['(int-add 1 nil) 'int-add]
            (mapv :form (get-in local-vars ['y :resolution-path]))))
     (is (= (T s/Int) (-> local-vars (get 'y) :resolution-path first :type)))
     (is (= (T s/Int) (get-in local-vars ['z :type])))
     (is (= ['(int-add 2 3) 'int-add]
            (mapv :form (get-in local-vars ['z :resolution-path]))))
     (is (= (T s/Int) (-> local-vars (get 'z) :resolution-path first :type)))
     (is (= ['int-add]
            (mapv :form (get-in call-result [:context :refs]))))
     (is (every? some? (mapv :type (get-in call-result [:context :refs])))))))

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
     'skeptic.test-examples/sample-annotated-fn
     'skeptic.test-examples/sample-half-annotated-fn
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
     'skeptic.test-examples/sample-bad-annotation-fn ['(int-add not-an-int 2)
                                                  [(incm/mismatched-ground-type-msg {:expr '(int-add not-an-int 2)
                                                                                              :arg 'not-an-int}
                                                                                             (T s/Str)
                                                                                             (T s/Int))]])))

(deftest failing-functions
  (in-test-examples
   (are [f errors] (= (set (partition 2 errors))
                      (result-pairs (check-fn test-dict f)))
     'skeptic.test-examples/sample-bad-fn ['(int-add nil x)
                                           [(incm/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-bad-let-fn ['(int-add x y)
                                               [(incm/mismatched-nullable-msg {:expr '(int-add x y) :arg 'y} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-let-bad-fn ['(int-add 1 nil)
                                               [(incm/mismatched-nullable-msg {:expr '(int-add 1 nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-line-body ['(int-add nil x)
                                                    [(incm/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-line-let-body ['(int-add 1 (f x))
                                                        [(incm/mismatched-nullable-msg {:expr '(int-add 1 (f x)) :arg '(f x)} (s/maybe s/Any) s/Int)]
                                                        '(int-add 2 3 4 nil)
                                                        [(incm/mismatched-nullable-msg {:expr '(int-add 2 3 4 nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                        '(int-add nil x)
                                                        [(incm/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]
                                                        '(int-add 2 nil)
                                                        [(incm/mismatched-nullable-msg {:expr '(int-add 2 nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                        '(int-add w 1 x y z)
                                                        [(incm/mismatched-nullable-msg {:expr '(int-add w 1 x y z) :arg 'w} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-mismatched-types ['(int-add x "hi")
                                                     [(incm/mismatched-ground-type-msg {:expr '(int-add x "hi") :arg "hi"} (T s/Str) (T s/Int))]]
     'skeptic.test-examples/sample-let-mismatched-types ['(int-add x s)
                                                         [(incm/mismatched-ground-type-msg {:expr '(int-add x s) :arg 's} (T s/Str) (T s/Int))]]
     'skeptic.test-examples/sample-let-fn-bad1-fn ['(int-add y nil)
                                                   [(incm/mismatched-nullable-msg {:expr '(int-add y nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-arity-fn ['(int-add x y z nil)
                                                   [(incm/mismatched-nullable-msg {:expr '(int-add x y z nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                   '(int-add x y nil)
                                                   [(incm/mismatched-nullable-msg {:expr '(int-add x y nil) :arg nil} (s/maybe s/Any) s/Int)]
                                                   '(int-add x nil)
                                                   [(incm/mismatched-nullable-msg {:expr '(int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-metadata-fn ['(int-add x nil)
                                                [(incm/mismatched-nullable-msg {:expr '(int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-doc-fn ['(int-add x nil)
                                           [(incm/mismatched-nullable-msg {:expr '(int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-doc-and-metadata-fn ['(int-add x nil)
                                                        [(incm/mismatched-nullable-msg {:expr '(int-add x nil) :arg nil} (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-fn-once ['(int-add y nil)
                                            [(incm/mismatched-nullable-msg {:expr '(int-add y nil) :arg nil} (s/maybe s/Any) s/Int)]])))

(deftest no-implicit-parametric-generalization-regression
  (in-test-examples
   (is (empty? (check-fn test-dict 'skeptic.test-examples/sample-bad-parametric-fn)))))

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
         (vec (sut/check-ns (typed-decls/typed-ns-entries {} 'skeptic.core-fns)
                            'skeptic.core-fns
                            (File. "src/skeptic/core_fns.clj")
                            {})))))

(deftest check-ns-localizes-read-failures
  (let [temp-file (doto (File/createTempFile "skeptic-read-failure" ".clj")
                    (.deleteOnExit))
        _ (spit temp-file "(ns skeptic.test-examples)\n(def ok 1)\n(def broken [)\n")
        results (vec (sut/check-ns test-dict
                                   'skeptic.test-examples
                                   temp-file
                                   {:remove-context true}))]
    (is (= 1 (count results)))
    (is (= :exception (:report-kind (first results))))
    (is (= :read (:phase (first results))))
    (is (= (.getPath temp-file) (get-in (first results) [:location :file])))))

(deftest check-ns-reads-auto-resolved-keywords-in-target-ns
  (require 'skeptic.test-examples)
  (let [results (vec (sut/check-ns (typed-decls/typed-ns-entries {} 'skeptic.test-examples)
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
            :line 40
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
            :line 153
            :column 5}
           (select-keys (:location result) [:file :line :column])))
    (is (= 'skeptic.test-examples/sample-bad-let-fn
           (:enclosing-form result)))))

(deftest call-mismatch-summary-uses-single-focused-input
  (in-test-examples
   (let [result (first (check-fn test-dict 'skeptic.test-examples/sample-let-fn-bad1-fn))
         summary (inrep/report-summary result)
         [error] (:errors summary)]
     (is (= '(int-add y nil) (:blame result)))
     (is (re-find #"(?s)^nil\s+\tin\s+\(int-add y nil\)\s+" (strip-ansi error)))
     (is (not (re-find #"(?s)^\(int-add y nil\)\s+\tin\s+\(int-add y nil\)\s+" (strip-ansi error))))
     (is (or (str/includes? (strip-ansi error) "expected type")
             (str/includes? (strip-ansi error) "is nullable, but expected is not"))))))

(deftest annotated-wrapper-regression
  (in-test-examples
   (is (= ['(int-add nil x)
           [(incm/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
          (result-errors (check-fn test-dict 'skeptic.test-examples/sample-annotated-bad-fn))))))

(deftest checking-annotated-wrapper-regression
  (in-test-examples
   (is (= [] (check-fn test-dict 'skeptic.test-examples/sample-named-input-fn)))
   (is (= [] (check-fn test-dict 'skeptic.test-examples/sample-named-output-fn)))
   (is (= [] (check-fn test-dict 'skeptic.test-examples/sample-constrained-output-fn)))
   (is (= ['x
             [(incm/mismatched-output-schema-msg
               {:expr 'sample-bad-constrained-output-fn
                :arg 'x}
              (T s/Str)
              (T (s/constrained s/Int pos?)))]]
          (result-errors (check-fn test-dict 'skeptic.test-examples/sample-bad-constrained-output-fn))))))

(deftest symbol-output-annotation-regression
  (let [form (->> 'skeptic.schema.collect/fully-qualify-str
                  (source/get-fn-code {})
                  read-string)
        results (vec (sut/check-s-expr schema-collect-dict
                                       form
                                       {:ns 'skeptic.schema.collect
                                        :source-file schema-collect-file
                                        :remove-context true}))]
    (is (= [] results))))

(deftest collect-annotations-output-annotation-regression
  (let [form (->> 'skeptic.schema.collect/collect-schemas
                  (source/get-fn-code {})
                  read-string)
        results (vec (sut/check-s-expr schema-collect-dict
                                       form
                                       {:ns 'skeptic.schema.collect
                                        :source-file schema-collect-file
                                        :remove-context true}))]
    (is (= [] results))))

(deftest static-call-examples-check-ns
  (let [results (vec (sut/check-ns static-call-examples-dict
                                   'skeptic.static-call-examples
                                   static-call-examples-file
                                   {:remove-context true}))
        count-result (some #(when (= 'skeptic.static-call-examples/bad-count-default
                                      (:enclosing-form %))
                              %)
                           results)
        nested-call-result (some #(when (= 'skeptic.static-call-examples/nested-multi-step-failure
                                            (:enclosing-form %))
                                    %)
                                 results)
        rebuilt-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                             (:enclosing-form %))
                                     %)
                                  results)
        rebuilt-nested-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-nested-user
                                                    (:enclosing-form %))
                                            %)
                                         results)]
    (is (= [(incm/mismatched-output-schema-msg {:expr 'bad-count-default
                                                         :arg '(get counts :count "zero")}
                                                        (ato/union-type [(T s/Int)
                                                                         (T s/Str)])
                                                        (T s/Int))]
           (:errors count-result)))
    (is (= [(incm/mismatched-ground-type-msg
              {:expr '(nested-multi-step-takes-str (get (nested-multi-step-g) :value))
               :arg '(. clojure.lang.RT (clojure.core/get (nested-multi-step-g) :value))}
              (T s/Int)
              (T s/Str))]
           (:errors nested-call-result)))
    (is (some #(str/includes? % "{:name Keyword, :nickname (maybe Str)}")
              (:errors rebuilt-user-result)))
    (is (= 1 (count (:errors rebuilt-user-result))))
    (is (not-any? #(str/includes? % "Problem fields:") (:errors rebuilt-user-result)))
    (is (not-any? #(str/includes? % "[:user :name]") (:errors rebuilt-nested-user-result)))
    (is (not-any? #(contains? #{'skeptic.static-call-examples/required-name
                                'skeptic.static-call-examples/optional-nickname
                                'skeptic.static-call-examples/nickname-with-default
                                'skeptic.static-call-examples/rebuilt-user
                                'skeptic.static-call-examples/rebuilt-nested-user
                                'skeptic.static-call-examples/merge-fields
                                'skeptic.static-call-examples/nested-multi-step-success}
                              (:enclosing-form %))
                   results))))

(deftest output-mismatch-renders-canonical-map-types
  (let [results (vec (sut/check-ns static-call-examples-dict
                                   'skeptic.static-call-examples
                                   static-call-examples-file
                                   {:remove-context true}))
        result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                              (:enclosing-form %))
                        %)
                     results)
        error (first (:errors result))]
    (is (some? result))
    (is (.contains error "{:name Keyword, :nickname (maybe Str)}"))
    (is (.contains error "{:name Str, :nickname (maybe Str)}"))
    (is (not (.contains error "\":name : Keyword\"")))))

(deftest output-summary-highlights-path-or-drops-redundant-self-context
  (let [results (vec (sut/check-ns static-call-examples-dict
                                   'skeptic.static-call-examples
                                   static-call-examples-file
                                   {:remove-context true}))
        count-result (some #(when (= 'skeptic.static-call-examples/bad-count-default
                                      (:enclosing-form %))
                              %)
                           results)
        rebuilt-user-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                             (:enclosing-form %))
                                     %)
                                  results)
        count-error (-> count-result inrep/report-summary :errors first strip-ansi)
        rebuilt-error (-> rebuilt-user-result inrep/report-summary :errors first strip-ansi)]
    (is (re-find #"(?s)^\(get counts :count \"zero\"\)\s+has an output mismatch against the declared return type\." count-error))
    (is (not (re-find #"(?s)^\(get counts :count \"zero\"\)\s+\tin\s+\(get counts :count \"zero\"\)" count-error)))
    (is (str/includes? count-error "Str but expected Int"))
    (is (re-find #"(?s)^\[:name\]\s+\tin\s+\{:name :bad, :nickname \(get user :nickname\)\}" rebuilt-error))
    (is (str/includes? rebuilt-error "[:name] has Keyword but expected Str"))))

(deftest nested-output-mismatch-renders-field-paths
  (let [results (vec (sut/check-ns static-call-examples-dict
                                   'skeptic.static-call-examples
                                   static-call-examples-file
                                   {:remove-context true}))
        result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-nested-user
                              (:enclosing-form %))
                        %)
                     results)]
    (is (some? result))
    (is (some #(str/includes? % "declared return type") (:errors result)))
    (is (= 1 (count (:errors result))))
    (is (not-any? #(str/includes? % "[:user :name]") (:errors result)))
    (is (= [{:kind :map-key :key :user}
            {:kind :map-key :key :name}]
           (-> result :cast-results first :path)))))

(deftest check-results-carry-cast-metadata
  (let [results (vec (sut/check-ns static-call-examples-dict
                                   'skeptic.static-call-examples
                                   static-call-examples-file
                                   {:remove-context true}))
        nested-result (some #(when (= 'skeptic.static-call-examples/nested-multi-step-failure
                                      (:enclosing-form %))
                               %)
                            results)
        output-result (some #(when (= 'skeptic.static-call-examples/bad-rebuilt-user
                                      (:enclosing-form %))
                               %)
                            results)]
    (doseq [result [nested-result output-result]]
      (is (some? result))
      (is (= :term (:blame-side result)))
      (is (= :positive (:blame-polarity result)))
      (is (keyword? (:rule result)))
      (is (some? (:expected-type result)))
      (is (some? (:actual-type result)))
      (is (map? (:cast-result result)))
      (is (seq (:cast-results result))))
    (is (= "(nested-multi-step-takes-str (get (nested-multi-step-g) :value))"
           (:source-expression nested-result)))
    (is (= {:file "src/skeptic/static_call_examples.clj"
            :line 88
            :column 3}
           (select-keys (:location nested-result) [:file :line :column])))
    (is (= ["(get (nested-multi-step-g) :value)"]
           (:focus-sources nested-result)))))

(deftest examples-maybe-multi-step-check-ns
  (let [results (vec (sut/check-ns examples-dict
                                   'skeptic.examples
                                   examples-file
                                   {:remove-context true}))]
    (is (some #(when (= 'skeptic.examples/flat-maybe-base-type-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/flat-maybe-nil-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/nested-maybe-base-type-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (some #(when (= 'skeptic.examples/nested-maybe-nil-failure
                        (:enclosing-form %))
                 %)
              results))
    (is (nil? (some #(when (= 'skeptic.examples/flat-maybe-success
                             (:enclosing-form %))
                    %)
                  results)))
    (is (nil? (some #(when (= 'skeptic.examples/nested-maybe-success
                             (:enclosing-form %))
                    %)
                  results)))))

(deftest resolved-helper-failures-use-final-reduced-types
  (let [flat-results (vec (sut/check-ns test-dict
                                        'skeptic.test-examples
                                        test-file
                                        {:remove-context true}))
        flat-result (some #(when (= 'skeptic.test-examples/flat-multi-step-failure
                                    (:enclosing-form %))
                             %)
                          flat-results)
        nested-results (vec (sut/check-ns static-call-examples-dict
                                          'skeptic.static-call-examples
                                          static-call-examples-file
                                          {:remove-context true}))
        nested-result (some #(when (= 'skeptic.static-call-examples/nested-multi-step-failure
                                      (:enclosing-form %))
                               %)
                            nested-results)]
    (is (= '(flat-multi-step-takes-str (flat-multi-step-g))
           (:blame flat-result)))
    (is (= [(incm/mismatched-ground-type-msg {:expr '(flat-multi-step-takes-str (flat-multi-step-g))
                                                       :arg '(flat-multi-step-g)}
                                                      (T s/Int)
                                                      (T s/Str))]
           (:errors flat-result)))
    (is (nil? (some #(when (= 'skeptic.test-examples/flat-multi-step-success
                               (:enclosing-form %))
                      %)
                    flat-results)))

    (is (= '(nested-multi-step-takes-str (get (nested-multi-step-g) :value))
           (:blame nested-result)))
    (is (= [(incm/mismatched-ground-type-msg
              {:expr '(nested-multi-step-takes-str (get (nested-multi-step-g) :value))
               :arg '(. clojure.lang.RT (clojure.core/get (nested-multi-step-g) :value))}
              (T s/Int)
              (T s/Str))]
           (:errors nested-result)))
    (is (nil? (some #(when (= 'skeptic.static-call-examples/nested-multi-step-success
                               (:enclosing-form %))
                      %)
                    nested-results)))))

(deftest check-s-expr-uses-resolved-helper-types
  (in-test-examples
   (let [results (vec (check-fn test-dict
                                'skeptic.test-examples/flat-multi-step-failure
                                {:remove-context true}))
         result (first results)]
     (is (= 1 (count results)))
     (is (= '(flat-multi-step-takes-str (flat-multi-step-g))
            (:blame result)))
     (is (= [(incm/mismatched-ground-type-msg
               {:expr '(flat-multi-step-takes-str (flat-multi-step-g))
                :arg '(flat-multi-step-g)}
               (T s/Int)
               (T s/Str))]
            (:errors result))))))

(deftest nested-call-mismatch-renders-field-paths
  (in-test-examples
   (let [result (first (check-fn test-dict 'skeptic.test-examples/nested-map-input-failure
                                 {:remove-context true}))]
     (is (some? result))
     (is (= '(takes-nested-name {:user {:name :bad}})
            (:blame result)))
     (is (some #(str/includes? % "[:user :name]") (:errors result)))
     (is (= [{:kind :map-key :key :user}
             {:kind :map-key :key :name}]
            (-> result :cast-results first :path))))))

(deftest vector-call-mismatch-renders-index-paths
  (in-test-examples
   (let [result (first (check-fn test-dict 'skeptic.test-examples/vector-input-failure
                                 {:remove-context true}))]
     (is (some? result))
     (is (= '(takes-int-pair (bad-int-pair-helper))
            (:blame result)))
     (is (some #(str/includes? % "[1]") (:errors result)))
     (is (= [{:kind :vector-index :index 1}]
            (-> result :cast-results first :path))))))

(deftest vector-literal-tuples-derive-homogeneous-views-at-check-boundary
  (in-test-examples
   (is (= [] (check-fn test-dict 'skeptic.test-examples/vector-triple-to-homogeneous-success)))
   (is (= [] (check-fn test-dict 'skeptic.test-examples/vector-triple-to-fixed-success)))
   (let [pair-result (first (check-fn test-dict
                                      'skeptic.test-examples/vector-triple-to-pair-failure
                                      {:remove-context true}))
         quad-result (first (check-fn test-dict
                                      'skeptic.test-examples/vector-triple-to-quad-failure
                                      {:remove-context true}))]
     (is (some? pair-result))
     (is (= '(takes-int-pair [x y z]) (:blame pair-result)))
     (is (= :vector-arity-mismatch (-> pair-result :cast-result :reason)))

     (is (some? quad-result))
     (is (= '(takes-int-quad [x y z]) (:blame quad-result)))
     (is (= :vector-arity-mismatch (-> quad-result :cast-result :reason))))))

(deftest printer-path-renders-only-user-facing-data
  (in-test-examples
   (let [result (first (check-fn test-dict 'skeptic.test-examples/nested-map-input-failure
                                 {:remove-context true}))
         summary (inrep/report-summary result)
         printed (str/join "\n"
                           (concat (map (fn [[label value]]
                                          (str label value))
                                        (core/report-fields summary))
                                   (:errors summary)))]
     (is (some? result))
     (is (str/includes? printed "[:user :name]"))
     (assert-no-ui-internals printed))))

(deftest declaration-based-recursion-and-forward-refs
  (in-test-examples
   (is (= [] (check-fn test-dict 'skeptic.test-examples/forward-declared-caller)))
   (is (= [] (check-fn test-dict 'skeptic.test-examples/self-recursive-identity)))
   (is (= [] (check-fn test-dict 'skeptic.test-examples/mutual-recursive-left)))
   (is (= [] (check-fn test-dict 'skeptic.test-examples/mutual-recursive-right)))))

(deftest check-ns-does-not-mutate-declaration-dicts
  (let [before test-dict]
    (vec (sut/check-ns test-dict
                       'skeptic.test-examples
                       test-file
                       {:remove-context true}))
    (is (= before test-dict))))

(deftest namespace-checking-keeps-going-after-declaration-errors
  (let [{:keys [entries errors]} (typed-decls/typed-ns-results {} 'skeptic.best-effort-examples)
        results (vec (concat errors
                             (sut/check-ns entries
                                           'skeptic.best-effort-examples
                                           best-effort-file
                                           {:remove-context true})))
        declaration-error (some #(when (= :declaration (:phase %)) %) results)
        stray-form-result (some #(when (= 'skeptic.best-effort-examples/good-call
                                        (:enclosing-form %))
                                   %)
                                results)]
    (is (some? declaration-error))
    (is (nil? stray-form-result))
    (is (= 1 (count results)))))

(deftest check-namespace-full-flow-localizes-declaration-errors
  (let [results (sut/check-namespace {:remove-context true}
                                     'skeptic.best-effort-examples
                                     best-effort-file)
        declaration-errors (filterv #(= :declaration (:phase %)) results)
        expression-results (filterv #(not= :declaration (:phase %)) results)]
    (is (= 1 (count declaration-errors)))
    (is (= :exception (:report-kind (first declaration-errors))))
    (is (= 'skeptic.best-effort-examples/invalid-schema-decl
           (:blame (first declaration-errors))))
    (is (zero? (count expression-results)))))

(deftest check-namespace-localizes-load-failure
  (let [results (sut/check-namespace {}
                                     'skeptic.nonexistent.namespace.that.does.not.exist
                                     (File. "nonexistent.clj"))]
    (is (= 1 (count results)))
    (is (= :exception (:report-kind (first results))))
    (is (= :load (:phase (first results))))
    (is (= 'skeptic.nonexistent.namespace.that.does.not.exist
           (:namespace (first results))))))

(deftest check-ns-localizes-expression-exceptions-and-continues
  (let [real-analyze sut/analyze-source-exprs
        exprs (vec (sut/ns-exprs test-file))
        exploding-form (some #(when (= 'sample-bad-fn (second %)) %) exprs)]
    (is (some? exploding-form))
    (with-redefs [sut/analyze-source-exprs (fn [dict ns-sym source-file exprs]
                                             (if (= exploding-form (first exprs))
                                               (throw (ex-info "boom during analysis" {}))
                                               (real-analyze dict ns-sym source-file exprs)))]
      (let [results (vec (sut/check-ns test-dict
                                       'skeptic.test-examples
                                       test-file
                                       {:remove-context true}))
            exception-result (some #(when (= :expression (:phase %)) %) results)
            later-mismatch (some #(when (= 'skeptic.test-examples/sample-mismatched-types
                                          (:enclosing-form %))
                                   %)
                                results)]
        (is (some? exception-result))
        (is (= 'skeptic.test-examples/sample-bad-fn
               (:enclosing-form exception-result)))
        (is (= "boom during analysis" (:exception-message exception-result)))
        (is (some? later-mismatch))))))

(deftest check-ns-localizes-lazy-expression-exceptions-and-continues
  (let [real-check-resolved-form sut/check-resolved-form
        exprs (vec (sut/ns-exprs test-file))
        exploding-form (some #(when (= 'sample-bad-fn (second %)) %) exprs)]
    (is (some? exploding-form))
    (with-redefs [sut/check-resolved-form (fn [dict ns-sym source-form analyzed opts]
                                            (if (= exploding-form source-form)
                                              (map (fn [_]
                                                     (throw (ex-info "boom during realization" {})))
                                                   [::explode])
                                              (real-check-resolved-form dict ns-sym source-form analyzed opts)))]
      (let [form-results (sut/check-ns-form test-dict
                                            'skeptic.test-examples
                                            test-file
                                            exploding-form
                                            {:remove-context true})
            exception-result (first form-results)
            results (vec (sut/check-ns test-dict
                                       'skeptic.test-examples
                                       test-file
                                       {:remove-context true}))
            later-mismatch (some #(when (= 'skeptic.test-examples/sample-mismatched-types
                                          (:enclosing-form %))
                                   %)
                                results)]
        (is (= 1 (count form-results)))
        (is (= :expression (:phase exception-result)))
        (is (= 'skeptic.test-examples/sample-bad-fn
               (:enclosing-form exception-result)))
        (is (= "boom during realization" (:exception-message exception-result)))
        (is (some? later-mismatch))))))

(defn single-failure?
  [f blame]
  (let [results (vec (check-fn test-dict f))
        result (first results)]
    (cond
      (not= 1 (count results)) (do (println (format "%d results returned" (count results))) false)
      (not= blame (:blame result)) (do (println (format "Actual blame \"%s\" does not match expected blame \"%s\""
                                                        (:blame result) blame))
                                       false)
      (empty? (:errors result)) (do (println "No errors returned in result")
                                    false)
      :else true)))

(deftest checking-conditional-input-contracts
  (in-test-examples
   (are [f] (= [] (check-fn test-dict f))
     'skeptic.test-examples/conditional-input-int-success
     'skeptic.test-examples/conditional-input-str-success
     'skeptic.test-examples/cond-pre-input-int-success
     'skeptic.test-examples/cond-pre-input-str-success
     'skeptic.test-examples/either-input-int-success
     'skeptic.test-examples/either-input-str-success
     'skeptic.test-examples/if-input-int-success
     'skeptic.test-examples/if-input-str-success
     'skeptic.test-examples/both-any-int-input-success)

   (are [f blame] (single-failure? f blame)
     'skeptic.test-examples/conditional-input-keyword-failure '(takes-conditional-branch :bad)
     'skeptic.test-examples/cond-pre-input-keyword-failure '(takes-cond-pre-branch :bad)
     'skeptic.test-examples/either-input-keyword-failure '(takes-either-branch :bad)
     'skeptic.test-examples/if-input-keyword-failure '(takes-if-branch :bad)
     'skeptic.test-examples/both-any-int-input-str-failure '(takes-both-any-int "hi")
     'skeptic.test-examples/both-int-str-input-int-failure '(takes-both-int-str 1)
     'skeptic.test-examples/both-int-str-input-str-failure '(takes-both-int-str "hi"))))

(deftest checking-conditional-output-contracts
  (in-test-examples
   (are [f] (= [] (check-fn test-dict f))
     'skeptic.test-examples/conditional-output-int-success
     'skeptic.test-examples/conditional-output-str-success
     'skeptic.test-examples/cond-pre-output-int-success
     'skeptic.test-examples/cond-pre-output-str-success
     'skeptic.test-examples/either-output-int-success
     'skeptic.test-examples/either-output-str-success
     'skeptic.test-examples/if-output-int-success
     'skeptic.test-examples/if-output-str-success
     'skeptic.test-examples/both-any-int-output-success)

   (are [f blame] (single-failure? f blame)
     'skeptic.test-examples/conditional-output-keyword-failure :bad
     'skeptic.test-examples/cond-pre-output-keyword-failure :bad
     'skeptic.test-examples/either-output-keyword-failure :bad
     'skeptic.test-examples/if-output-keyword-failure :bad
     'skeptic.test-examples/both-int-str-output-int-failure 1
     'skeptic.test-examples/both-int-str-output-str-failure "hi")))

(deftest conditional-contract-contains-key-refinement
  (in-test-examples
   (are [f] (= [] (check-fn test-dict f))
     'skeptic.test-examples/conditional-map-if-a-success
     'skeptic.test-examples/conditional-map-if-b-success
     'skeptic.test-examples/conditional-map-alias-success
     'skeptic.test-examples/conditional-map-cond-thread-success)

   (are [f blame] (single-failure? f blame)
     'skeptic.test-examples/conditional-map-if-a-bad-branch '(takes-has-b x)
     'skeptic.test-examples/conditional-map-if-b-bad-branch '(takes-has-a x)
     'skeptic.test-examples/optional-map-contains-does-not-refine '(takes-has-a x))))

(deftest conditional-contract-cond-thread-output-construction
  (in-test-examples
   (are [f] (= [] (check-fn test-dict f))
     'skeptic.test-examples/mk-ab-int-success
     'skeptic.test-examples/mk-ab-str-success
     'skeptic.test-examples/mk-ab-int-returns-ab
     'skeptic.test-examples/mk-ab-str-returns-ab)))

(deftest nested-conditional-contract-cond-thread
  (in-test-examples
   (are [f] (= [] (check-fn test-dict f))
     'skeptic.test-examples/mk-takes-a-or-b-success-int
     'skeptic.test-examples/mk-takes-a-or-b-success-str
     'skeptic.test-examples/mk-takes-a-or-b-success-nil
     'skeptic.test-examples/self-test-success
     'skeptic.test-examples/nested-self-test-success
     'skeptic.test-examples/conditional-test-success-a
     'skeptic.test-examples/conditional-test-success-b
     'skeptic.test-examples/nested-conditional-test-success-a
     'skeptic.test-examples/nested-conditional-test-success-b)

   (are [f blame] (single-failure? f blame)
     'skeptic.test-examples/mk-takes-a-or-b-failure-outer '(takes-a-or-b {:a :nope})
     'skeptic.test-examples/mk-takes-a-or-b-failure-inner '(takes-a-or-b {:c {:d :nope}})
     'skeptic.test-examples/mk-takes-a-or-b-failure-inner-inner '(takes-a-or-b {:c {:a :nope}}))))
