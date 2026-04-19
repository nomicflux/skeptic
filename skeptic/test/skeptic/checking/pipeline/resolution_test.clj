(ns skeptic.checking.pipeline.resolution-test
  (:require [clojure.test :refer [deftest is]]
            [schema.core :as s]
            [skeptic.checking.pipeline :as sut]
            [skeptic.checking.pipeline.support :as ps]
            [skeptic.inconsistence.mismatch :as incm]))

(deftest resolution-path-resolutions
  (let [results (ps/check-fixture 'skeptic.test-examples.control-flow/sample-bad-local-provenance-fn
                                  {:keep-empty true})
        call-result (first (filter #(= '(int-add x y z) (:blame %)) results))
        local-vars (get-in call-result [:context :local-vars])]
    (is (some? call-result))
    (is (= [] (:errors call-result)))
    (is (= (ps/T s/Any) (get-in local-vars ['x :type])))
    (is (= [] (get-in local-vars ['x :resolution-path])))
    (is (= (ps/T s/Int) (get-in local-vars ['y :type])))
    (is (= ['(int-add 1 nil) 'int-add]
           (mapv :form (get-in local-vars ['y :resolution-path]))))
    (is (= (ps/T s/Int) (-> local-vars (get 'y) :resolution-path first :type)))
    (is (= (ps/T s/Int) (get-in local-vars ['z :type])))
    (is (= ['(int-add 2 3) 'int-add]
           (mapv :form (get-in local-vars ['z :resolution-path]))))
    (is (= (ps/T s/Int) (-> local-vars (get 'z) :resolution-path first :type)))
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
        nested-results (vec (sut/check-ns ps/static-call-examples-dict
                                          'skeptic.static-call-examples
                                          ps/static-call-examples-file
                                          {:remove-context true}))
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
