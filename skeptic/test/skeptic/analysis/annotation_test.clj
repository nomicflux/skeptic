(ns skeptic.analysis.annotation-test
  (:require [skeptic.analysis.annotation :as sut]
            [clojure.test :refer [deftest is are]]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.walk :as walk]))

(defn clean-for-test
  [expr]
  (walk/postwalk
   (fn [x]
     (cond-> x
       (map? x)
       (select-keys [:args :idx :path :body :ret :bindings
                     :statements :op :val :fn :init])))
   expr))

(deftest idx-expression-test
  (let [expr (ana.jvm/analyze '(+ 1 2))]
    (is (= (-> expr
               (assoc :idx 0)
               (assoc-in [:args 0 :idx] 1)
               (assoc-in [:args 1 :idx] 2))
           (sut/idx-expression expr)))
    (is (= {:args [{:op :const, :val 1}
                   {:op :const, :val 2}],
            :op :static-call}
           (clean-for-test expr)))
    (is (= {:args [{:idx 1, :op :const, :val 1}
                   {:idx 2, :op :const, :val 2}],
            :idx 0,
            :op :static-call}
           (-> expr sut/idx-expression clean-for-test))))

  (let [expr (ana.jvm/analyze '(* (+ 1 2) (let [x 3] (println x) (- 4))))]
    (is (= (-> expr
               (assoc :idx 0)
               (assoc-in [:args 0 :idx] 1)
               (assoc-in [:args 1 :idx] 2)
               (assoc-in [:args 1 :body :idx] 3)
               (assoc-in [:args 0 :args 1 :idx] 4)
               (assoc-in [:args 1 :body :ret :idx] 5)
               (assoc-in [:args 0 :args 0 :idx] 6)
               (assoc-in [:args 1 :body :ret :args 0 :idx] 7)
               (assoc-in [:args 1 :bindings 0 :idx] 8)
               (assoc-in [:args 1 :bindings 0 :init :idx] 9)
               (assoc-in [:args 1 :body :statements 0 :idx] 10)
               (assoc-in [:args 1 :body :statements 0 :fn :idx] 11)
               (assoc-in [:args 1 :body :statements 0 :args 0 :idx] 12))
           (sut/idx-expression expr)))

    (is (= {:args
            [{:args [{:op :const, :val 1}
                     {:op :const, :val 2}],
              :op :static-call}
             {:body
              {:ret {:args [{:op :const, :val 4}],
                     :op :static-call},
               :statements [{:args [{:op :local}],
                             :op :invoke
                             :fn {:op :var}}],
               :op :do},
              :bindings [{:op :binding
                          :init {:op :const, :val 3}}],
              :op :let}],
            :op :static-call}
           (clean-for-test expr)))

    (is (= {:args
            [{:args [{:op :const, :val 1, :idx 6}
                     {:op :const, :val 2, :idx 4}],
              :op :static-call
              :idx 1}
             {:body
              {:ret {:args [{:op :const, :val 4, :idx 7}],
                     :op :static-call
                     :idx 5},
               :statements [{:args [{:op :local, :idx 12}],
                             :op :invoke
                             :fn {:op :var, :idx 11}
                             :idx 10}],
               :op :do
               :idx 3},
              :bindings [{:op :binding
                          :idx 8
                          :init {:op :const, :val 3, :idx 9}}],
              :op :let
              :idx 2}],
            :op :static-call
            :idx 0}
           (-> expr sut/idx-expression clean-for-test)))))

(deftest unanalyze-test
  (is (= '(+ 1 2)
         (-> '(+ 1 2) ana.jvm/analyze sut/unanalyze)))
  (is (= '(* (+ 1 2) (let [x 3] (println 3) (- 4)))
         (-> '(* (+ 1 2) (let [x 3] (println 3) (- 4)))
             ana.jvm/analyze
             sut/unanalyze))))
