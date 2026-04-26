(ns skeptic.analysis.type-algebra-test
  (:require [clojure.test :refer [deftest is testing]]
            [skeptic.analysis.type-algebra :as sut]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [tp]]))

(defn- int-t [prov] (at/->GroundT prov :int 'Int))

(deftest type-substitute-vector-threads-refs-test
  (testing "vector substitute (no replacement applies): refs count = items"
    (let [vt (at/->VectorT tp [(int-t tp) (int-t tp)] true)
          result (sut/type-substitute vt 'X (int-t tp))
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest type-substitute-set-threads-refs-test
  (testing "set substitute: refs count = members"
    (let [st (at/->SetT tp #{(int-t tp)} true)
          result (sut/type-substitute st 'X (int-t tp))
          refs (:refs (prov/of result))]
      (is (= 1 (count refs))))))

(deftest type-substitute-seq-threads-refs-test
  (testing "seq substitute: refs count = items"
    (let [sq (at/->SeqT tp [(int-t tp) (int-t tp) (int-t tp)] true)
          result (sut/type-substitute sq 'X (int-t tp))
          refs (:refs (prov/of result))]
      (is (= 3 (count refs))))))

(deftest type-substitute-map-threads-refs-test
  (testing "map substitute: refs flattens k/v provs"
    (let [mt (at/->MapT tp {(at/->ValueT tp (at/->GroundT tp :keyword 'Keyword) :a)
                            (int-t tp)})
          result (sut/type-substitute mt 'X (int-t tp))
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest type-substitute-union-threads-refs-test
  (testing "union substitute: refs count = members"
    (let [ut (at/->UnionT tp #{(int-t tp) (at/->GroundT tp :str 'Str)})
          result (sut/type-substitute ut 'X (int-t tp))
          refs (:refs (prov/of result))]
      (is (= 2 (count refs))))))

(deftest type-free-vars-on-conditional-test
  (let [tv-x (at/->TypeVarT tp 'X)
        tv-y (at/->TypeVarT tp 'Y)
        cond-type (at/->ConditionalT tp [[:integer? tv-x nil] [:string? tv-y nil]])]
    (is (= #{'X 'Y} (sut/type-free-vars cond-type)))))

(deftest type-substitute-on-conditional-test
  (let [tv-x (at/->TypeVarT tp 'X)
        int-t' (int-t tp)
        cond-type (at/->ConditionalT tp [[:integer? tv-x nil] [:string? tv-x nil]])
        substituted (sut/type-substitute cond-type 'X int-t')]
    (is (at/conditional-type? substituted))
    (is (= 2 (count (:branches substituted))))
    (is (at/type=? int-t' (second (first (:branches substituted)))))
    (is (at/type=? int-t' (second (second (:branches substituted)))))
    (is (= :integer? (first (first (:branches substituted)))))))
