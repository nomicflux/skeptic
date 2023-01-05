(ns skeptic.checking-test
  (:require [skeptic.checking :as sut]
            [skeptic.test-examples :as test-examples]
            [clojure.test :refer [deftest is are]]
            [skeptic.schematize :as schematize]
            [skeptic.inconsistence :as inconsistence]
            [schema.core :as s]
            [clojure.walk :as walk]))

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
                                                           [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-bad-let-fn ['(skeptic.test-examples/int-add x y)
                                                               [(inconsistence/mismatched-nullable-msg 'y (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-let-bad-fn ['(skeptic.test-examples/int-add 1 nil)
                                                               [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-multi-line-body ['(skeptic.test-examples/int-add nil x)
                                                                    [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-multi-line-let-body ['(skeptic.test-examples/int-add 2 3 4 nil)
                                                                        [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]
                                                                        '(skeptic.test-examples/int-add 2 nil)
                                                                        [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]
                                                                        '(skeptic.test-examples/int-add w 1 x y z)
                                                                        [(inconsistence/mismatched-nullable-msg 'w (s/maybe s/Any) s/Int)]
                                                                        '(skeptic.test-examples/int-add 1 (f x))
                                                                        [(inconsistence/mismatched-nullable-msg '(f x) (s/maybe s/Any) s/Int)]
                                                                        '(skeptic.test-examples/int-add nil x)
                                                                        [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-mismatched-types ['(skeptic.test-examples/int-add x "hi")
                                                                     [(inconsistence/mismatched-ground-type-msg "hi" s/Str s/Int)]]
                     'skeptic.test-examples/sample-let-mismatched-types ['(skeptic.test-examples/int-add x s)
                                                                         [(inconsistence/mismatched-ground-type-msg 's s/Str s/Int)]]
                     'skeptic.test-examples/sample-let-fn-bad1-fn ['(skeptic.test-examples/int-add y nil)
                                                                   [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     ;;'skeptic.test-examples/sample-let-fn-bad2-fn [""]
                     'skeptic.test-examples/sample-multi-arity-fn ['(skeptic.test-examples/int-add x nil)
                                                                   [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]
                                                                   '(skeptic.test-examples/int-add x y nil)
                                                                   [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]
                                                                   '(skeptic.test-examples/int-add x y z nil)
                                                                   [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-metadata-fn ['(skeptic.test-examples/int-add x nil)
                                                                [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-doc-fn ['(skeptic.test-examples/int-add x nil)
                                                           [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-doc-and-metadata-fn ['(skeptic.test-examples/int-add x nil)
                                                                        [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]]
                     'skeptic.test-examples/sample-fn-once ['(skeptic.test-examples/int-add y nil)
                                                            [(inconsistence/mismatched-nullable-msg nil (s/maybe s/Any) s/Int)]])))


(defn clean-callbacks
  [expr]
  (walk/postwalk #(if (map? %) (dissoc % :dep-callback) %) expr))

(defn unannotate-expr
  [expr]
  (walk/postwalk #(if (and (map? %) (contains? % :expr)) (:expr %) %) expr))

(deftest analyse-let-test
  (let [analysed (->> '(let [x 1] (+ 1 x))
                      schematize/macroexpand-all
                      sut/annotate-expr
                      sut/analyse-let)]
    (is (= [{:expr 1, :idx 3, :local-vars {}}
             {:expr
              {:expr [{:expr '+, :idx 5} {:expr 1, :idx 6} {:expr 'x, :idx 7}],
               :idx 8},
              :local-vars {'x {:placeholder 3}}}
             {:expr
              [{:expr 'let*, :idx 1}
               {:expr [{:expr 'x, :idx 2} {:expr 1, :idx 3}], :idx 4}
               {:expr [{:expr '+, :idx 5} {:expr 1, :idx 6} {:expr 'x, :idx 7}],
                :idx 8}],
              :idx 9}]
           (clean-callbacks analysed)))
    (is (= '(1 (+ 1 x) (let* [x 1] (+ 1 x)))
           (unannotate-expr analysed))))

  (let [analysed (->> '(let [x (+ 1 2) y (+ 3 x)] (+ 7 x) (+ x y))
                      schematize/macroexpand-all
                      sut/annotate-expr
                      sut/analyse-let)]
    (is (= [{:expr [{:expr '+, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}],
              :idx 6,
             :local-vars {}}
            {:expr [{:expr '+, :idx 8} {:expr 3, :idx 9} {:expr 'x, :idx 10}],
              :idx 11,
              :local-vars {'x {:placeholder 6}}}
             {:expr
              {:expr [{:expr '+, :idx 13} {:expr 7, :idx 14} {:expr 'x, :idx 15}],
               :idx 16},
              :local-vars {'x {:placeholder 6}
                           'y {:placeholder 11}}}
             {:expr
              {:expr [{:expr '+, :idx 17} {:expr 'x, :idx 18} {:expr 'y, :idx 19}],
               :idx 20},
              :local-vars {'x {:placeholder 6}
                           'y {:placeholder 11}}}
             {:expr
              [{:expr 'let*, :idx 1}
               {:expr
                [{:expr 'x, :idx 2}
                 {:expr [{:expr '+, :idx 3} {:expr 1, :idx 4} {:expr 2, :idx 5}],
                  :idx 6}
                 {:expr 'y, :idx 7}
                 {:expr [{:expr '+, :idx 8} {:expr 3, :idx 9} {:expr 'x, :idx 10}],
                  :idx 11}],
                :idx 12}
               {:expr [{:expr '+, :idx 13} {:expr 7, :idx 14} {:expr 'x, :idx 15}],
                :idx 16}
               {:expr [{:expr '+, :idx 17} {:expr 'x, :idx 18} {:expr 'y, :idx 19}],
                :idx 20}],
              :idx 21}]
           (clean-callbacks analysed)))
    (is (= '((+ 1 2)
             (+ 3 x)
             (+ 7 x)
             (+ x y)
             (let* [x (+ 1 2) y (+ 3 x)] (+ 7 x) (+ x y)))
           (unannotate-expr analysed)))))
