(ns skeptic.analysis.annotate.coll-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.types :as at]))

(def int-t (at/->GroundT :int 'Int))
(def str-t (at/->GroundT :str 'Str))

(deftest seqish-element-type-test
  (testing "homogeneous VectorT"
    (is (= int-t (aac/seqish-element-type (at/->VectorT [int-t int-t] true)))))
  (testing "homogeneous SeqT"
    (is (= int-t (aac/seqish-element-type (at/->SeqT [int-t] true)))))
  (testing "non-collection"
    (is (nil? (aac/seqish-element-type str-t)))))

(deftest coll-first-type-test
  (testing "first element of VectorT"
    (is (= int-t (aac/coll-first-type (at/->VectorT [int-t str-t] false)))))
  (testing "homogeneous SeqT element type"
    (is (= int-t (aac/coll-first-type (at/->SeqT [int-t] true)))))
  (testing "empty vector"
    (is (nil? (aac/coll-first-type (at/->VectorT [] true))))))

(deftest coll-rest-output-type-test
  (testing "tail VectorT for 3-element vector"
    (let [v (at/->VectorT [int-t str-t int-t] false)
          r (aac/coll-rest-output-type v)]
      (is (at/vector-type? r))
      (is (= 2 (count (:items r))))))
  (testing "1-element vector yields SeqT"
    (let [r (aac/coll-rest-output-type (at/->VectorT [int-t] true))]
      (is (at/seq-type? r))))
  (testing "homogeneous SeqT"
    (let [r (aac/coll-rest-output-type (at/->SeqT [int-t] true))]
      (is (at/seq-type? r)))))

(deftest coll-take-prefix-type-test
  (testing "prefix of length n"
    (let [v (at/->VectorT [int-t str-t int-t] false)
          p (aac/coll-take-prefix-type v 2)]
      (is (= 2 (count (:items p))))))
  (testing "n >= length returns full vector"
    (let [v (at/->VectorT [int-t str-t] false)
          p (aac/coll-take-prefix-type v 10)]
      (is (= 2 (count (:items p)))))))

(deftest coll-drop-prefix-type-test
  (testing "suffix after skipping n"
    (let [v (at/->VectorT [int-t str-t int-t] false)
          d (aac/coll-drop-prefix-type v 1)]
      (is (= 2 (count (:items d))))))
  (testing "n >= length yields empty vector"
    (let [d (aac/coll-drop-prefix-type (at/->VectorT [int-t] true) 3)]
      (is (and (at/vector-type? d) (empty? (:items d)))))))

(deftest concat-output-type-test
  (testing "homogeneous SeqT from two seq-shaped args"
    (let [out (aac/concat-output-type [{:type (at/->SeqT [int-t] true)}
                                       {:type (at/->SeqT [int-t] true)}])]
      (is (at/seq-type? out))
      (is (:homogeneous? out))))
  (testing "empty args"
    (let [out (aac/concat-output-type [])]
      (is (and (at/seq-type? out)
               (= [at/Dyn] (:items out))
               (:homogeneous? out))))))

(deftest into-output-type-test
  (testing "vector target"
    (let [out (aac/into-output-type [{:type (at/->VectorT [int-t] true)}
                                    {:type (at/->SeqT [int-t] true)}])]
      (is (at/vector-type? out))))
  (testing "seq target"
    (let [out (aac/into-output-type [{:type (at/->SeqT [str-t] true)}
                                    {:type (at/->SeqT [str-t] true)}])]
      (is (at/seq-type? out)))))

(deftest invoke-nth-output-type-test
  (testing "element at known index"
    (let [args [{:type (at/->VectorT [int-t str-t int-t] false)}
                {:op :const :val 1}]
          out (aac/invoke-nth-output-type args)]
      (is (= str-t out))))
  (testing "heterogeneous SeqT with literal index — not inferred"
    (let [args [{:type (at/->SeqT [int-t str-t] false)}
                {:op :const :val 0}]
          out (aac/invoke-nth-output-type args)]
      (is (nil? out)))))
