(ns skeptic.checking-test
  (:require [skeptic.checking :as sut]
            [skeptic.test-examples :as test-examples]
            [clojure.test :refer [deftest is are]]
            [skeptic.schematize :as schematize]))

(def test-dict (sut/block-in-ns 'skeptic.test-examples
                                (schematize/ns-schemas 'skeptic.test-examples)))
(def test-refs (ns-map 'skeptic.test-examples))

(defn manual-check
  ([f]
   (manual-check f {}))
  ([f opts]
   (sut/block-in-ns 'skeptic.test-examples
                    (sut/check-fn test-refs test-dict f opts))))

(defn manual-annotate
  [f]
  (sut/block-in-ns 'skeptic.test-examples
                   (sut/annotate-fn test-refs test-dict f)))

(deftest working-functions
  (sut/block-in-ns 'skeptic.test-examples
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
  (sut/block-in-ns 'skeptic.test-examples
                   (are [f errors] (= errors
                                      (mapcat (juxt :blame :errors) (sut/check-fn test-refs test-dict f)))
                     'skeptic.test-examples/sample-bad-fn ['(skeptic.test-examples/int-add nil x)
                                                           ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-bad-let-fn ['(skeptic.test-examples/int-add x y)
                                                               ["Actual is nullable (y as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-let-bad-fn ['(skeptic.test-examples/int-add 1 nil)
                                                               ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-multi-line-body ['(skeptic.test-examples/int-add nil x)
                                                                    ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-multi-line-let-body ['(skeptic.test-examples/int-add 2 3 4 nil)
                                                                        ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]
                                                                        '(skeptic.test-examples/int-add 2 nil)
                                                                        ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]
                                                                        '(skeptic.test-examples/int-add w 1 x y z)
                                                                        ["Actual is nullable (w as (maybe Any)) but expected is not (Int)"]
                                                                        '(skeptic.test-examples/int-add 1 (f x))
                                                                        ["Actual is nullable ((f x) as (maybe Any)) but expected is not (Int)"]
                                                                        '(skeptic.test-examples/int-add nil x)
                                                                        ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-mismatched-types ['(skeptic.test-examples/int-add x "hi")
                                                                     ["Mismatched types: \"hi\" is Str, but expected is Int"]]
                     'skeptic.test-examples/sample-let-mismatched-types ['(skeptic.test-examples/int-add x s)
                                                                         ["Mismatched types: s is Str, but expected is Int"]]
                     'skeptic.test-examples/sample-let-fn-bad1-fn ['(skeptic.test-examples/int-add y nil)
                                                                   ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     ;;'skeptic.test-examples/sample-let-fn-bad2-fn [""]
                     'skeptic.test-examples/sample-multi-arity-fn ['(skeptic.test-examples/int-add x nil)
                                                                   ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]
                                                                   '(skeptic.test-examples/int-add x y nil)
                                                                   ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]
                                                                   '(skeptic.test-examples/int-add x y z nil)
                                                                   ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-metadata-fn ['(skeptic.test-examples/int-add x nil)
                                                                ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-doc-fn ['(skeptic.test-examples/int-add x nil)
                                                           ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-doc-and-metadata-fn ['(skeptic.test-examples/int-add x nil)
                                                                        ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]]
                     'skeptic.test-examples/sample-fn-once ['(skeptic.test-examples/int-add y nil)
                                                                        ["Actual is nullable (nil as (maybe Any)) but expected is not (Int)"]])))
