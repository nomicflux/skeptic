(ns skeptic.analysis.malli-spec.bridge-multi-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.malli-spec.bridge :as sut]
            [skeptic.provenance :as prov]
            [skeptic.analysis.types :as at]))

(def tp (prov/make-provenance :inferred (quote test-sym) (quote skeptic.test) nil [] :clj))

(deftest multi-with-keyword-dispatch-emits-values-descriptors
  (is (= (at/->ConditionalT
          tp
          [(at/->ConditionalBranch :a (at/->GroundT tp :int 'Int) {:path [:tag] :values [:a]} nil)
           (at/->ConditionalBranch :b (at/->GroundT tp :str 'Str) {:path [:tag] :values [:b]} nil)])
         (sut/malli-spec->type tp [:multi {:dispatch :tag} [:a :int] [:b :string]]))))

(deftest multi-default-branch-has-nil-descriptor
  (is (= (at/->ConditionalT
          tp
          [(at/->ConditionalBranch :a (at/->GroundT tp :int 'Int) {:path [:tag] :values [:a]} nil)
           (at/->ConditionalBranch :malli.core/default (at/->GroundT tp :keyword 'Keyword) nil nil)])
         (sut/malli-spec->type tp [:multi {:dispatch :tag}
                                   [:a :int]
                                   [:malli.core/default :keyword]]))))

(deftest multi-with-fn-dispatch-emits-nil-descriptors
  (let [result (sut/malli-spec->type tp [:multi {:dispatch (fn [x] (:tag x))}
                                         [:a :int]
                                         [:b :string]])]
    (is (at/conditional-type? result))
    (is (= 2 (count (:branches result))))
    (is (= [:a :b] (mapv :pred (:branches result))))
    (is (every? nil? (mapv :descriptor (:branches result))))))

(deftest multi-with-nested-map-branches
  (let [result (sut/malli-spec->type tp
                                     [:multi {:dispatch :type}
                                      [:int [:map [:type [:= :int]] [:value :int]]]
                                      [:str [:map [:type [:= :str]] [:value :string]]]])]
    (is (at/conditional-type? result))
    (is (= [:int :str] (mapv :pred (:branches result))))
    (is (= [{:path [:type] :values [:int]}
            {:path [:type] :values [:str]}]
           (mapv :descriptor (:branches result))))
    (is (every? at/map-type? (mapv :type (:branches result))))))

(deftest multi-single-branch
  (is (= (at/->ConditionalT
          tp
          [(at/->ConditionalBranch :a (at/->GroundT tp :int 'Int) {:path [:tag] :values [:a]} nil)])
         (sut/malli-spec->type tp [:multi {:dispatch :tag} [:a :int]]))))
