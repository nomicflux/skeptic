(ns skeptic.provenance-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.provenance :as sut]))

(deftest provenance-record-shape
  (let [p (sut/->Provenance :schema 'foo/bar 'foo nil)]
    (is (sut/provenance? p))
    (is (= :schema (:source p)))
    (is (= 'foo/bar (:qualified-sym p)))
    (is (= 'foo (:declared-in p)))
    (is (nil? (:var-meta p)))))

(deftest provenance-predicate
  (is (sut/provenance? (sut/->Provenance :native 'a/b 'a {})))
  (is (not (sut/provenance? {:source :schema}))))

(deftest merge-provenances-picks-highest-rank
  (let [ps (sut/->Provenance :schema 'a/b 'a nil)
        pm (sut/->Provenance :malli-spec 'a/b 'a nil)
        result (sut/merge-provenances ps pm)]
    (is (= :malli-spec (:source result)))
    (is (sut/provenance? result))))

(deftest merge-provenances-rank-order
  (let [ps (sut/->Provenance :schema 'a/b 'a nil)
        pn (sut/->Provenance :native 'a/b 'a nil)
        result (sut/merge-provenances pn ps)]
    (is (= :schema (:source result)))))

(deftest merge-provenances-reduce-three-way
  (let [pn (sut/->Provenance :native 'x/y 'x nil)
        pm (sut/->Provenance :malli-spec 'x/y 'x nil)
        ps (sut/->Provenance :schema 'x/y 'x nil)
        result (reduce sut/merge-provenances [pn pm ps])]
    (is (sut/provenance? result))
    (is (= :malli-spec (:source result)))))

(deftest merge-provenances-reduce-three-way-permuted
  (let [pn (sut/->Provenance :native 'x/y 'x nil)
        pm (sut/->Provenance :malli-spec 'x/y 'x nil)
        ps (sut/->Provenance :schema 'x/y 'x nil)
        result (reduce sut/merge-provenances [ps pn pm])]
    (is (sut/provenance? result))
    (is (= :malli-spec (:source result)))))
