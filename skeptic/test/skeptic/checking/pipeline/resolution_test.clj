(ns skeptic.checking.pipeline.resolution-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is]]
            [schema.core :as s]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.test-helpers :refer [is-type=]]))

(deftest resolution-path-resolutions
  (let [results (ps/check-fixture 'skeptic.test-examples.control-flow/sample-bad-local-provenance-fn
                                  {:keep-empty true})
        call-result (first (filter #(= '(int-add x y z) (:blame %)) results))
        local-vars (get-in call-result [:context :local-vars])]
    (is (some? call-result))
    (is (empty? (:errors call-result)))
    (is-type= (ps/T s/Any) (get-in local-vars ['x :type]))
    (is (empty? (get-in local-vars ['x :resolution-path])))
    (is-type= (ps/T s/Int) (get-in local-vars ['y :type]))
    (is (= ['(int-add 1 nil) 'int-add]
           (mapv :form (get-in local-vars ['y :resolution-path]))))
    (is-type= (ps/T s/Int) (-> local-vars (get 'y) :resolution-path first :type))
    (is-type= (ps/T s/Int) (get-in local-vars ['z :type]))
    (is (= ['(int-add 2 3) 'int-add]
           (mapv :form (get-in local-vars ['z :resolution-path]))))
    (is-type= (ps/T s/Int) (-> local-vars (get 'z) :resolution-path first :type))
    (is (= ['int-add]
           (mapv :form (get-in call-result [:context :refs]))))
    (is (every? some? (mapv :type (get-in call-result [:context :refs]))))))

(deftest shadow-provenance-uses-param-binding
  (let [results (ps/check-fixture 'skeptic.test-examples.control-flow/sample-shadow-bad-fn
                                  {:keep-empty true})
        call-result (first (filter #(= '(int-add x y z) (:blame %)) results))
        local-vars (get-in call-result [:context :local-vars])]
    (is (some? call-result))
    (is (= [] (get-in local-vars ['x :resolution-path]))
        "param x shadowed by inner let must have empty resolution path, not point to (int-add 9 9)")))

(deftest resolved-helper-failures-use-final-reduced-types
  (let [flat-results (ps/check-fixture-ns 'skeptic.test-examples.resolution
                                          {:remove-context true})
        flat-result (some #(when (= 'skeptic.test-examples.resolution/flat-multi-step-failure
                                    (:enclosing-form %))
                             %)
                          flat-results)
        nested-results (:results (sut/check-ns 'skeptic.static-call-examples
                                               ps/static-call-examples-file
                                               {:remove-context true
                                                :accessor-summaries (ps/summaries-for 'skeptic.static-call-examples ps/static-call-examples-file)}))
        nested-result (some #(when (= 'skeptic.static-call-examples/nested-multi-step-failure
                                      (:enclosing-form %))
                               %)
                            nested-results)]
    (is (= '(flat-multi-step-takes-str (flat-multi-step-g))
           (:blame flat-result)))
    (is (= [(incm/mismatched-ground-type-msg {:expr '(flat-multi-step-takes-str (flat-multi-step-g))
                                              :arg '(flat-multi-step-g)}
                                             (ps/T s/Int)
                                             (ps/T s/Str))]
           (:errors flat-result)))
    (is (nil? (some #(when (= 'skeptic.test-examples.resolution/flat-multi-step-success
                              (:enclosing-form %))
                       %)
                    flat-results)))
    (is (= '(nested-multi-step-takes-str (get (nested-multi-step-g) :value))
           (:blame nested-result)))
    (is (= [(incm/mismatched-ground-type-msg
             {:expr '(nested-multi-step-takes-str (get (nested-multi-step-g) :value))
              :arg '(. clojure.lang.RT (clojure.core/get (nested-multi-step-g) :value))}
             (ps/T s/Int)
             (ps/T s/Str))]
           (:errors nested-result)))
    (is (nil? (some #(when (= 'skeptic.static-call-examples/nested-multi-step-success
                              (:enclosing-form %))
                       %)
                    nested-results)))))

(deftest check-s-expr-uses-resolved-helper-types
  (let [results (vec (ps/check-fixture 'skeptic.test-examples.resolution/flat-multi-step-failure
                                       {:remove-context true}))
        result (first results)]
    (is (= 1 (count results)))
    (is (= '(flat-multi-step-takes-str (flat-multi-step-g))
           (:blame result)))
    (is (= [(incm/mismatched-ground-type-msg
             {:expr '(flat-multi-step-takes-str (flat-multi-step-g))
              :arg '(flat-multi-step-g)}
             (ps/T s/Int)
             (ps/T s/Str))]
           (:errors result)))))

(deftest declaration-based-recursion-and-forward-refs
  (is (= [] (ps/check-fixture 'skeptic.test-examples.resolution/forward-declared-caller)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.resolution/self-recursive-identity)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.resolution/mutual-recursive-left)))
  (is (= [] (ps/check-fixture 'skeptic.test-examples.resolution/mutual-recursive-right))))

(deftest failing-functions
  (are [sym errors] (= (set (partition 2 errors))
                       (ps/result-pairs (ps/check-fixture sym)))
    'skeptic.test-examples.resolution/sample-let-fn-bad1-fn
    ['(int-add y nil)
     [(incm/mismatched-schema-msg {:expr '(int-add y nil) :arg nil}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]

    'skeptic.test-examples.resolution/sample-let-fn-bad2-fn
    ['(int-add y x)
     [(incm/mismatched-schema-msg {:expr '(int-add y x) :arg 'y}
                                  (ps/T (s/eq nil))
                                  (ps/T s/Int))]]))

(deftest check-ns-reads-auto-resolved-keywords-in-target-ns
  (require 'skeptic.test-examples.resolution)
  (let [results (ps/check-fixture-ns 'skeptic.test-examples.resolution
                                     {:keep-empty true
                                      :remove-context true})]
    (is (seq results))
    (is (some #(= "(int-add x (::s/key2 y))" (:source-expression %)) results))
    (is (= {:blame '(int-add x (:schema.core/key2 y))
            :source-expression "(int-add x (::s/key2 y))"
            :expanded-expression '(int-add x (:schema.core/key2 y))
            :enclosing-form 'skeptic.test-examples.resolution/sample-namespaced-keyword-fn
            :focuses []}
           (some #(when (= "(int-add x (::s/key2 y))" (:source-expression %))
                    (dissoc (select-keys % [:blame :source-expression :expanded-expression :location :enclosing-form :focuses])
                            :location))
                 results)))
    (is (= {:file (ps/fixture-path-for-ns 'skeptic.test-examples.resolution)
            :line 9
            :column 5}
           (some #(when (= "(int-add x (::s/key2 y))" (:source-expression %))
                    (select-keys (:location %) [:file :line :column]))
                 results)))))

(deftest instance-method-value-on-bigdecimal-typechecks
  (is (= [] (ps/check-fixture 'skeptic.test-examples.resolution/sample-bigdecimal-method-value-fn))))

(deftest call-mismatch-summary-uses-single-focused-input
  (let [result (first (ps/check-fixture 'skeptic.test-examples.resolution/sample-let-fn-bad1-fn))
        summary (inrep/report-summary result)
        [error] (:errors summary)]
    (is (= '(int-add y nil) (:blame result)))
    (is (re-find #"(?s)^nil\s+\tin\s+\(int-add y nil\)\s+" (ps/strip-ansi error)))
    (is (not (re-find #"(?s)^\(int-add y nil\)\s+\tin\s+\(int-add y nil\)\s+" (ps/strip-ansi error))))
    (is (or (str/includes? (ps/strip-ansi error) "expected type")
            (str/includes? (ps/strip-ansi error) "is nullable, but expected is not")))))
