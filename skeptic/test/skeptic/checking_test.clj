(ns skeptic.checking-test
  (:require [skeptic.checking :as sut]
            [skeptic.test-examples :as test-examples]
            [clojure.test :refer [deftest is are]]
            [skeptic.schematize :as schematize]
            [skeptic.inconsistence :as inconsistence]
            [schema.core :as s])
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

(deftest working-functions
  (in-test-examples
   (are [f] (empty? (try (sut/check-fn test-refs test-dict f)
                         (catch Exception e
                           (throw (ex-info "Exception checking function"
                                           {:function f
                                            :test-refs test-refs
                                            :test-dict test-dict
                                            :error e})))))
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
                                           [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add nil x) nil (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-bad-let-fn ['(skeptic.test-examples/int-add x y)
                                               [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add x y) 'y (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-let-bad-fn ['(skeptic.test-examples/int-add 1 nil)
                                               [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add 1 nil) nil (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-line-body ['(skeptic.test-examples/int-add nil x)
                                                    [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add nil x) nil (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-multi-line-let-body ['(skeptic.test-examples/int-add nil x)
                                                        [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add nil x) nil (s/maybe s/Any) s/Int)]
                                                        '(skeptic.test-examples/int-add 1 (f x))
                                                        [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add 1 (f x)) '(f x) (s/maybe s/Any) s/Int)]
                                                        '(skeptic.test-examples/int-add 2 3 4 nil)
                                                        [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add 2 3 4 nil) nil (s/maybe s/Any) s/Int)]
                                                        '(skeptic.test-examples/int-add w 1 x y z)
                                                        [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add w 1 x y z) 'w (s/maybe s/Any) s/Int)]
                                                        '(skeptic.test-examples/int-add 2 nil)
                                                        [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add 2 nil) nil (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-mismatched-types ['(skeptic.test-examples/int-add x "hi")
                                                     [(inconsistence/mismatched-ground-type-msg '(skeptic.test-examples/int-add x "hi") "hi" s/Str s/Int)]]
     'skeptic.test-examples/sample-let-mismatched-types ['(skeptic.test-examples/int-add x s)
                                                         [(inconsistence/mismatched-ground-type-msg '(skeptic.test-examples/int-add x s) 's s/Str s/Int)]]
     'skeptic.test-examples/sample-let-fn-bad1-fn ['(skeptic.test-examples/int-add y nil)
                                                   [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add y nil) nil (s/maybe s/Any) s/Int)]]
     ;;'skeptic.test-examples/sample-let-fn-bad2-fn [""]
     'skeptic.test-examples/sample-multi-arity-fn ['(skeptic.test-examples/int-add x y z nil)
                                                   [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add x y z nil) nil (s/maybe s/Any) s/Int)]
                                                   '(skeptic.test-examples/int-add x y nil)
                                                   [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add x y nil) nil (s/maybe s/Any) s/Int)]
                                                   '(skeptic.test-examples/int-add x nil)
                                                   [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add x nil) nil (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-metadata-fn ['(skeptic.test-examples/int-add x nil)
                                                [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add x nil) nil (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-doc-fn ['(skeptic.test-examples/int-add x nil)
                                           [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add x nil) nil (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-doc-and-metadata-fn ['(skeptic.test-examples/int-add x nil)
                                                        [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add x nil) nil (s/maybe s/Any) s/Int)]]
     'skeptic.test-examples/sample-fn-once ['(skeptic.test-examples/int-add y nil)
                                            [(inconsistence/mismatched-nullable-msg '(skeptic.test-examples/int-add y nil) nil (s/maybe s/Any) s/Int)]])))
