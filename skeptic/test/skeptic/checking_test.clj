(ns skeptic.checking-test
  (:require [clojure.test :refer [are deftest is]]
            [schema.core :as s]
            [skeptic.checking :as sut]
            [skeptic.inconsistence :as inconsistence]
            [skeptic.schematize :as schematize]
            [skeptic.test-examples])
  (:import [java.io File]))

(defmacro in-test-examples
  [& body]
  `(sut/block-in-ns 'skeptic.test-examples (File. "test/skeptic/test_examples.clj")
                    ~@body))

(def test-dict (in-test-examples (schematize/ns-schemas {} 'skeptic.test-examples)))

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
     'skeptic.test-examples/sample-bad-schema-fn [])))

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
   (let [results (vec (sut/check-ns test-dict 'skeptic.test-examples {:remove-context true}))]
     (is (seq results))
     (is (some #(= '(int-add x "hi") (:blame %)) results))
     (is (not-any? #(and (seq? (:blame %))
                         (= "schema.core" (namespace (first (:blame %)))))
                   results)))))

(deftest schema-wrapper-regression
  (in-test-examples
   (is (= ['(int-add nil x)
           [(inconsistence/mismatched-nullable-msg {:expr '(int-add nil x) :arg nil} (s/maybe s/Any) s/Int)]]
          (result-errors (check-fn test-dict 'skeptic.test-examples/sample-schema-bad-fn))))))
