(ns skeptic.provenance-test
  (:require [clojure.test :refer [deftest is]]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as sut]))

(deftest provenance-record-shape
  (let [p (sut/make-provenance :schema 'foo/bar 'foo nil)]
    (is (sut/provenance? p))
    (is (= :schema (sut/source p)))
    (is (= 'foo/bar (:qualified-sym p)))
    (is (= 'foo (:declared-in p)))
    (is (nil? (:var-meta p)))))

(deftest provenance-predicate
  (is (sut/provenance? (sut/make-provenance :native 'a/b 'a {})))
  (is (not (sut/provenance? {:source :schema}))))

(deftest merge-provenances-picks-highest-rank
  (let [ps (sut/make-provenance :schema 'a/b 'a nil)
        pm (sut/make-provenance :malli 'a/b 'a nil)
        result (sut/merge-provenances ps pm)]
    (is (= :malli (sut/source result)))
    (is (sut/provenance? result))))

(deftest merge-provenances-rank-order
  (let [ps (sut/make-provenance :schema 'a/b 'a nil)
        pn (sut/make-provenance :native 'a/b 'a nil)
        result (sut/merge-provenances pn ps)]
    (is (= :schema (sut/source result)))))

(deftest merge-provenances-reduce-three-way
  (let [pn (sut/make-provenance :native 'x/y 'x nil)
        pm (sut/make-provenance :malli 'x/y 'x nil)
        ps (sut/make-provenance :schema 'x/y 'x nil)
        result (reduce sut/merge-provenances [pn pm ps])]
    (is (sut/provenance? result))
    (is (= :malli (sut/source result)))))

(deftest merge-provenances-reduce-three-way-permuted
  (let [pn (sut/make-provenance :native 'x/y 'x nil)
        pm (sut/make-provenance :malli 'x/y 'x nil)
        ps (sut/make-provenance :schema 'x/y 'x nil)
        result (reduce sut/merge-provenances [ps pn pm])]
    (is (sut/provenance? result))
    (is (= :malli (sut/source result)))))

(deftest make-provenance-rejects-invalid-source
  (is (thrown? IllegalArgumentException
               (sut/make-provenance :not-a-source 'a/b 'a nil)))
  (is (thrown? IllegalArgumentException
               (sut/make-provenance :also-not-a-source 'a/b 'a nil))))

(deftest of-reads-prov-field-from-semantic-type
  (let [p (sut/make-provenance :schema 'a/b 'a nil)
        t (at/->GroundT p :int 'Int)]
    (is (= p (sut/of t)))))

(deftest of-throws-on-value-without-provenance
  (is (thrown? IllegalArgumentException (sut/of {:not :a-type}))))

(deftest merge-schema-and-inferred-keeps-schema
  (let [ps (sut/make-provenance :schema 'a/b 'a nil)
        pi (sut/make-provenance :inferred 'a/b 'a nil)]
    (is (= :schema (sut/source (sut/merge-provenances ps pi))))
    (is (= :schema (sut/source (sut/merge-provenances pi ps))))))

(deftest make-provenance-defaults-empty-refs
  (let [p (sut/make-provenance :schema 'a/b 'a nil)]
    (is (= [] (:refs p)))))

(deftest make-provenance-five-arity-stores-refs
  (let [c1 (sut/make-provenance :schema 'x/y 'x nil)
        c2 (sut/make-provenance :native 'z/w 'z nil)
        p (sut/make-provenance :inferred nil 'a nil [c1 c2])]
    (is (= [c1 c2] (:refs p)))))

(deftest with-refs-replaces-refs
  (let [p (sut/make-provenance :schema 'a/b 'a nil)
        c1 (sut/make-provenance :native 'x/y 'x nil)
        c2 (sut/make-provenance :schema 'z/w 'z nil)
        result (sut/with-refs p [c1 c2])]
    (is (= [c1 c2] (:refs result)))))

(deftest provs-equal-only-if-refs-match
  (let [c1 (sut/make-provenance :schema 'x/y 'x nil)
        p1 (sut/make-provenance :schema 'a/b 'a nil [])
        p2 (sut/make-provenance :schema 'a/b 'a nil [c1])]
    (is (not= p1 p2))))

(deftest inferred-sets-empty-refs
  (let [p (sut/inferred {:name 'x :ns 'y})]
    (is (= [] (:refs p)))))
