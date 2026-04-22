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
        pm (sut/make-provenance :malli-spec 'a/b 'a nil)
        result (sut/merge-provenances ps pm)]
    (is (= :malli-spec (sut/source result)))
    (is (sut/provenance? result))))

(deftest merge-provenances-rank-order
  (let [ps (sut/make-provenance :schema 'a/b 'a nil)
        pn (sut/make-provenance :native 'a/b 'a nil)
        result (sut/merge-provenances pn ps)]
    (is (= :schema (sut/source result)))))

(deftest merge-provenances-reduce-three-way
  (let [pn (sut/make-provenance :native 'x/y 'x nil)
        pm (sut/make-provenance :malli-spec 'x/y 'x nil)
        ps (sut/make-provenance :schema 'x/y 'x nil)
        result (reduce sut/merge-provenances [pn pm ps])]
    (is (sut/provenance? result))
    (is (= :malli-spec (sut/source result)))))

(deftest merge-provenances-reduce-three-way-permuted
  (let [pn (sut/make-provenance :native 'x/y 'x nil)
        pm (sut/make-provenance :malli-spec 'x/y 'x nil)
        ps (sut/make-provenance :schema 'x/y 'x nil)
        result (reduce sut/merge-provenances [ps pn pm])]
    (is (sut/provenance? result))
    (is (= :malli-spec (sut/source result)))))

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
