(ns skeptic.analysis.value-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [schema.core :as s]
            [skeptic.analysis.class-oracle :as oracle]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]
            [skeptic.test-helpers :refer [is-type= T tp]]
            [skeptic.test-support.shared-worker :as shared-worker]))

(use-fixtures :once shared-worker/with-shared-worker)

(def ^:private other-prov
  (prov/make-provenance :schema 'other 'other.ns nil [] :clj))

(deftest join-container-owns-prov-test
  (testing "empty item seq does not crash; result carries anchor prov"
    (let [result (av/join tp [])]
      (is (= tp (prov/of result)))))
  (testing "non-empty items carry their own provs, but result's prov is the anchor"
    (let [int-t (at/->GroundT other-prov :int 'Int)
          str-t (at/->GroundT other-prov :str 'Str)
          result (av/join tp [int-t str-t])]
      (is (= tp (prov/of result))))))

(deftest type-of-value-literals-test
  (testing "scalars"
    (is (= (T s/Int) (av/type-of-value tp 1)))
    (is (= (T (s/eq nil)) (av/type-of-value tp nil)))
    (is (= (T s/Str) (av/type-of-value tp "x")))
    (is (= (T s/Keyword) (av/type-of-value tp :x)))
    (is (= (T s/Bool) (av/type-of-value tp true))))
  (testing "fine numeric grounds are preserved"
    (let [double-type (av/type-of-value tp 3.5)
          float-type (av/type-of-value tp (float 3.5))
          bigdec-type (av/type-of-value tp 3.5M)
          ratio-type (av/type-of-value tp 7/2)]
      (is (= {:class (oracle/host-handle Double)} (:ground double-type)))
      (is (= {:class (oracle/host-handle Float)} (:ground float-type)))
      (is (= {:class (oracle/host-handle java.math.BigDecimal)} (:ground bigdec-type)))
      (is (= {:class (oracle/host-handle clojure.lang.Ratio)} (:ground ratio-type)))
      (is (not= (:ground bigdec-type) (:ground float-type))))))

(deftest class->type-object-handle-is-dyn-test
  ;; Pre-split, class->type's (class? klass) branch excluded Object/java.lang.Object
  ;; and returned Dyn. Post-split a class arrives as a handle, so it takes the
  ;; (handle? klass) branch instead. That branch must honor the SAME Object
  ;; exclusion: an Object-resolved class is non-informative and must stay Dyn,
  ;; not become a concrete java.lang.Object ground that leaf-compares against
  ;; every declared type.
  (let [object-handle (oracle/host-handle Object)
        result (av/class->type tp object-handle)]
    (is (at/dyn-type? result))
    (is (not (at/ground-type? result)))))

(deftest class->type-canonical-handle-test
  ;; A canonical host class arrives from the worker as a handle. class->type must
  ;; map it to the SAME simple Skeptic ground its class form maps to (pre-split
  ;; via canonical-scalar-schema), host-side with no oracle round-trip, so an
  ;; inferred clojure.lang.Symbol return matches a declared s/Symbol instead of
  ;; producing a non-canonical {:class handle} ground that leaf-overlaps.
  (is (= (T s/Symbol) (av/class->type tp (oracle/host-handle clojure.lang.Symbol))))
  (is (= (T s/Keyword) (av/class->type tp (oracle/host-handle clojure.lang.Keyword))))
  (is (= (T s/Str) (av/class->type tp (oracle/host-handle java.lang.String))))
  (is (= (T s/Bool) (av/class->type tp (oracle/host-handle java.lang.Boolean))))
  (is (= (T s/Int) (av/class->type tp (oracle/host-handle java.lang.Long))))
  (is (at/numeric-dyn-type? (av/class->type tp (oracle/host-handle java.lang.Number))))
  ;; primitives intern via their TYPE field (Class/forName cannot load them)
  (is (= (T s/Bool) (av/class->type tp (oracle/host-handle Boolean/TYPE))))
  (is (= (T s/Int) (av/class->type tp (oracle/host-handle Integer/TYPE))))
  (is (= (T s/Int) (av/class->type tp (oracle/host-handle Long/TYPE)))))

(deftest type-of-value-collections-test
  (testing "empty list"
    (is-type= (at/->SeqT tp (at/pattern-from-prefix-tail [] nil) :sequential) (av/type-of-value tp '())))
  (testing "simple vector"
    (is-type= (at/->SeqT tp (at/pattern-from-prefix-tail [(T s/Int) (T s/Int)] nil) :vector) (av/type-of-value tp [1 2])))
  (testing "nested vector with map and set"
    (let [type (av/type-of-value tp '[1 {:a 2 :b {:c #{3 4}}} 5])]
      (is-type= (at/->SeqT tp
                           (at/pattern-from-prefix-tail
                            [(T s/Int)
                             (at/->MapT tp {(at/->ValueT tp (T s/Keyword) :a) (at/->ValueT tp (T s/Int) 2)
                                            (at/->ValueT tp (T s/Keyword) :b) (at/->ValueT tp (at/->MapT tp {(at/->ValueT tp (T s/Keyword) :c)
                                                                                                             (at/->ValueT tp (at/->SetT tp #{(T s/Int)} true) #{3 4})})
                                                                                           {:c #{3 4}})})
                             (T s/Int)]
                            nil)
                           :vector)
                type)))
  (testing "nested map"
    (let [type (av/type-of-value tp '{:a 1 :b [:z "hello" #{1 2}]
                                      :c {:d 7 :e {:f 9}}})]
      (is-type= (ato/normalize-type tp {(at/->ValueT tp (T s/Keyword) :a) (at/->ValueT tp (T s/Int) 1)
                                        (at/->ValueT tp (T s/Keyword) :b) (at/->ValueT tp (at/->SeqT tp
                                                                                                         (at/pattern-from-prefix-tail
                                                                                                          [(T s/Keyword)
                                                                                                           (T s/Str)
                                                                                                           (at/->SetT tp #{(T s/Int)} true)]
                                                                                                          nil)
                                                                                                         :vector)
                                                                                       [:z "hello" #{1 2}])
                                        (at/->ValueT tp (T s/Keyword) :c) (at/->ValueT tp (ato/normalize-type tp {(at/->ValueT tp (T s/Keyword) :d) (at/->ValueT tp (T s/Int) 7)
                                                                                                                  (at/->ValueT tp (T s/Keyword) :e) (at/->ValueT tp (ato/normalize-type tp {(at/->ValueT tp (T s/Keyword) :f)
                                                                                                                                                                                            (at/->ValueT tp (T s/Int) 9)})
                                                                                                                                                                 {:f 9})})
                                                                                       {:d 7 :e {:f 9}})})
                type))))

(deftest type-of-value-vector-arm-threads-refs-test
  (testing "vector input threads item provs into result's refs"
    (let [result (av/type-of-value tp [1 2])
          refs (:refs (:prov result))]
      (is (= 2 (count refs)))
      (is (every? #(= tp %) refs)))))

(deftest type-of-value-seq-arm-threads-refs-test
  (testing "seq input threads each item's prov into result's refs (closed seq)"
    (let [result (av/type-of-value tp '(1 2 3))
          refs (:refs (:prov result))]
      (is (= 3 (count refs))))))

(deftest type-of-value-set-arm-threads-refs-test
  (testing "set input threads joined element prov into result's refs"
    (let [result (av/type-of-value tp #{1 2})
          refs (:refs (:prov result))]
      (is (= 1 (count refs))))))

(deftest map-value-type-threads-refs-test
  (testing "map value threads flattened k/v provs into result's refs"
    (let [result (av/map-value-type tp {:a 1})
          refs (:refs (:prov result))]
      (is (= 2 (count refs))))))
