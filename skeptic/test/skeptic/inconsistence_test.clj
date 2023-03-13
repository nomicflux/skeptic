(ns skeptic.inconsistence-test
  (:require [skeptic.inconsistence :as sut]
            [clojure.test :refer [is are deftest]]
            [schema.core :as s]
            [skeptic.analysis.schema :as as]))

;; Used to generate useful messages, just need a placeholder for these tests
;; to check whether they flag an error or not
(def sample-ctx
  {:expr '(f x 2)
   :arg 'x})

(defn schema-or-value
  [s v]
  (as/valued-schema s v))

(deftest mismatched-maybe-test
  (is (nil? (sut/mismatched-maybe sample-ctx (s/maybe s/Int) (s/maybe s/Int))))
  (is (nil? (sut/mismatched-maybe sample-ctx (s/maybe s/Int) s/Int)))
  (is (nil? (sut/mismatched-maybe sample-ctx s/Int s/Int)))
  (is (nil? (sut/mismatched-maybe sample-ctx (s/maybe s/Any) s/Any)))
  (is (nil? (sut/mismatched-maybe sample-ctx s/Any (s/maybe s/Any))))

  (is (not (nil? (sut/mismatched-maybe sample-ctx s/Int (s/maybe s/Int))))))

(deftest mismatched-ground-types-test
  (is (nil? (sut/mismatched-ground-types sample-ctx (s/maybe s/Int) s/Str)))
  (is (nil? (sut/mismatched-ground-types sample-ctx s/Int (s/maybe s/Str))))
  (is (nil? (sut/mismatched-ground-types sample-ctx s/Any s/Int)))
  (is (nil? (sut/mismatched-ground-types sample-ctx s/Int s/Any)))

  (is (not (nil? (sut/mismatched-ground-types sample-ctx s/Int s/Str))))
  (is (not (nil? (sut/mismatched-ground-types sample-ctx s/Str s/Int))))
  (is (not (nil? (sut/mismatched-ground-types sample-ctx s/Bool s/Symbol))))
  (is (not (nil? (sut/mismatched-ground-types sample-ctx s/Bool s/Int)))))

(deftest apply-base-rules-test
  (is (empty? (sut/apply-base-rules sample-ctx s/Int s/Any)))
  (is (seq (sut/apply-base-rules sample-ctx s/Int s/Str)))
  (is (empty? (sut/apply-base-rules sample-ctx (s/maybe s/Int) s/Int)))
  (is (seq (sut/apply-base-rules sample-ctx s/Int (s/maybe s/Int)))))

(deftest mismatched-key-schemas
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword (s/maybe s/Int)} {(schema-or-value s/Keyword :a) (schema-or-value (s/maybe s/Int) nil)})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword (s/maybe s/Int)} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx
                                           {s/Keyword s/Int s/Str s/Str}
                                           {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                            (schema-or-value s/Str "b") (schema-or-value s/Str "hello")})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {:a s/Int})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {s/Keyword (s/maybe s/Int)} {:a s/Int})))

  ;; (is (seq (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {:a s/Str})))
  (is (seq (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value (s/maybe s/Int) nil)})))
  (is (seq (sut/apply-mismatches-by-key sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Str "hello")})))
  (is (seq (sut/apply-mismatches-by-key sample-ctx
                                        {s/Keyword s/Int s/Str s/Str}
                                        {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                         (schema-or-value s/Str "b") (schema-or-value s/Int 1)})))
  (is (seq (sut/apply-mismatches-by-key sample-ctx
                                        {s/Keyword s/Int s/Str s/Str}
                                        {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                         (schema-or-value s/Str "b") (schema-or-value s/Int 2)})))

  (is (empty? (sut/apply-mismatches-by-key sample-ctx {{:a s/Int} s/Str} {{:a s/Int} "hello"})))
  (is (empty? (sut/apply-mismatches-by-key sample-ctx {{(s/optional-key :a) s/Int} s/Str} {{:a s/Int} "hello"})))
  ;; (is (seq (sut/apply-mismatches-by-key sample-ctx {{:a s/Int} s/Str} {{:a s/Int} 1})))
  ;; (is (seq (sut/apply-mismatches-by-key sample-ctx {{(s/optional-key :a) s/Int} s/Str} {{:a s/Int} 1})))
  )

(s/defschema Update {:a s/Keyword :b (s/maybe s/Str) (s/optional-key :c) s/Int})
(s/defschema Item {:x s/Str :y s/Int})

(deftest check-keys-test
  (is (empty? (sut/check-keys sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/check-keys sample-ctx {:a s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/check-keys sample-ctx {(s/optional-key s/Keyword) s/Int} {})))
  (is (empty? (sut/check-keys sample-ctx {(s/optional-key s/Keyword) s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/check-keys sample-ctx {(s/optional-key :a) s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/check-keys sample-ctx {s/Keyword s/Int} {:a s/Int})))
  (is (empty? (sut/check-keys sample-ctx {:a s/Int} {:a s/Int})))
  (is (empty? (sut/check-keys sample-ctx {(s/optional-key :a) s/Int} {:a s/Int})))
  (is (empty? (sut/check-keys sample-ctx {(s/optional-key :a) s/Int} {(s/optional-key :a) s/Int})))

  (is (seq (sut/check-keys sample-ctx {s/Str s/Int} {:a s/Int})))
  (is (seq (sut/check-keys sample-ctx {:a s/Int} {(s/optional-key :a) s/Int})))
  (is (seq (sut/check-keys sample-ctx {} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (seq (sut/check-keys sample-ctx {s/Str s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (seq (sut/check-keys sample-ctx {:b s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  )

(deftest get-matching-schemas
  (is (= 1 (sut/get-by-matching-schema {s/Int 1 s/Keyword 2} 1)))
  (is (= 2 (sut/get-by-matching-schema {s/Int 1 s/Keyword 2} :a)))
  (is (= nil (sut/get-by-matching-schema {s/Int 1 s/Keyword 2} "x")))
  )

(deftest valued-get-test
  (is (= 1 (sut/valued-get {:a 1 :b 2} :a)))
  (is (= 1 (sut/valued-get {:a 1 :b 2} (as/valued-schema s/Keyword :a))))
  (is (= 1 (sut/valued-get {s/Keyword 1 :b 2} (as/valued-schema s/Keyword :a))))
  ;; Prefer value over schema
  (is (= 1 (sut/valued-get {:a 1 s/Keyword 2} (as/valued-schema s/Keyword :a))))
  (is (= nil (sut/valued-get {:c 1 :b 2} (as/valued-schema s/Keyword :a))))
  (is (= 2 (sut/valued-get {:a 1 s/Keyword 2} (as/valued-schema s/Keyword :b))))
  )

(deftest valued-compare-test
  (is (sut/valued-compare 1 1))
  (is (sut/valued-compare s/Int 1))
  (is (not (sut/valued-compare :a 1)))
  (is (sut/valued-compare 1 (as/valued-schema s/Int 1)))
  (is (sut/valued-compare s/Int (as/valued-schema s/Int 1)))
  (is (not (sut/valued-compare 1 (as/valued-schema s/Keyword :a))))
  (is (not (sut/valued-compare s/Int (as/valued-schema s/Keyword :a))))

  (is (sut/valued-compare {:a 1 :b 2} {:a 1 :b 2}))
  (is (sut/valued-compare {:a 1 :b 2} {:a 1 :b (as/valued-schema s/Int 2)}))
  (is (not (sut/valued-compare {:a 1 :b 2} {:a 1 :b (as/valued-schema s/Str "x")})))
  (is (sut/valued-compare {:a 1 :b 2} {:a (as/valued-schema s/Int 1) :b (as/valued-schema s/Int 2)}))
  (is (not (sut/valued-compare {:a 1 :b 2} {:a 1 (as/valued-schema s/Str "x") 2})))
  (is (sut/valued-compare {:a 1 :b 2} {(as/valued-schema s/Keyword :a) (as/valued-schema s/Int 1)
                                       (as/valued-schema s/Keyword :b) (as/valued-schema s/Int 2)}))
  (is (sut/valued-compare {:a 1 :b 2} {(as/valued-schema s/Keyword :a) 1
                                       (as/valued-schema s/Keyword :b) 2}))

  (is (sut/valued-compare {:a s/Int :b s/Int} {:a 1 :b 2}))
  (is (sut/valued-compare {s/Keyword s/Int :b s/Str} {:a 1 :b "x"}))
  (is (not (sut/valued-compare {s/Keyword s/Int :b s/Str} {:b 1 :a "x"})))

  (is (sut/valued-compare {:a s/Int :b s/Int} {:a (as/valued-schema s/Int 1) :b (as/valued-schema s/Int 2)}))
  (is (sut/valued-compare {s/Keyword s/Int :b s/Str} {:a (as/valued-schema s/Int 1) :b (as/valued-schema s/Str "x")}))
  (is (not (sut/valued-compare {s/Keyword s/Int :b s/Str} {:b (as/valued-schema s/Int 1) :a (as/valued-schema s/Str "x")})))

  (is (sut/valued-compare {s/Keyword s/Int :b s/Str} {s/Keyword s/Int :b s/Str}))
  (is (sut/valued-compare {:b s/Str}
                          {(schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}))
  (is (sut/valued-compare {:b (s/maybe s/Str)}
                          {(schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}))
  (is (sut/valued-compare {{:a (s/maybe s/Str)
                            (s/optional-key :b) s/Str}
                           s/Int}
                          {{(schema-or-value s/Keyword :a) (schema-or-value s/Str "here")
                            (schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}
                           (schema-or-value s/Int 1)}))
  (is (sut/valued-compare {{:a (s/maybe s/Str)
                            (s/optional-key :b) s/Str}
                           s/Int}
                          {{(schema-or-value s/Keyword :a) (schema-or-value s/Str "here")}
                           (schema-or-value s/Int 1)}))
  (is (not (sut/valued-compare {{:a (s/maybe s/Str)} s/Int}
                               {{(schema-or-value s/Keyword :a) (schema-or-value s/Str "here")
                                 (schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}
                                (schema-or-value s/Int 1)})))

  (is (sut/valued-compare (s/enum :a :b) :a))
  (is (sut/valued-compare (s/enum :a :b) (schema-or-value s/Any :a)))
  )

(deftest matches-map-test
  (is (sut/matches-map {:a 1 :b 2} :a 1))
  (is (not (sut/matches-map {:a 1 :b 2} :b 1)))
  (is (not (sut/matches-map {:a 1 :b 2} :c 1)))
  (is (sut/matches-map {:a s/Int :b 2} :a 1))
  (is (sut/matches-map {s/Keyword s/Str :b 2} :a "x"))
  (is (not (sut/matches-map {s/Keyword s/Str :b 2} :a 1)))

  (is (sut/matches-map {{:a 1 :b 2} "x"} {:a 1 :b 2} "x"))
  (is (sut/matches-map {{:a s/Int :b 2} "x"} {:a 1 :b 2} "x"))
  (is (sut/matches-map {{:a 1 s/Keyword 2} "x"} {:a 1 :b 2} "x"))
  (is (sut/matches-map {{:a 1 :b 2} s/Str} {:a 1 :b 2} "x"))

  (is (sut/matches-map {{{:a 1 :b 2} "x"} :x} {{:a 1 :b 2} "x"} :x))
  (is (sut/matches-map {{{:a s/Int :b 2} "x"} :x} {{:a 1 :b 2} "x"} :x))
  (is (sut/matches-map {{{:a 1 s/Keyword 2} "x"} :x} {{:a 1 :b 2} "x"} :x))
  (is (sut/matches-map {{{:a 1 :b 2} s/Str} :x} {{:a 1 :b 2} "x"} :x))
  (is (sut/matches-map {{{:a 1 :b 2} "x"} s/Keyword} {{:a 1 :b 2} "x"} :x))

  (is (sut/matches-map {{:z {:a 1 :b 2} :c 3} "x"} {:z {:a 1 :b 2} :c 3} "x"))
  (is (sut/matches-map {{:z {:a s/Int :b 2} :c 3} "x"} {:z {:a 1 :b 2} :c 3} "x"))
  (is (sut/matches-map {{:z {:a 1 s/Keyword 2} :c 3} "x"} {:z {:a 1 :b 2} :c 3} "x"))
  (is (sut/matches-map {{:z {:a 1 :b 2} s/Keyword 3} "x"} {:z {:a 1 :b 2} :c 3} "x"))
  (is (sut/matches-map {{:z {:a 1 :b 2} :c 3} s/Str} {:z {:a 1 :b 2} :c 3} "x"))
  (is (sut/matches-map {{s/Keyword {:a 1 :b 2} :c 3} "x"} {:z {:a 1 :b 2} :c 3} "x"))
  )

(deftest in-set-test
  (is (sut/in-set? {:a 1 :b 2} :a))
  (is (not (sut/in-set? {:a 1 :b 2} :c)))
  (is (sut/in-set? {:a s/Int :b 2} :a))
  (is (sut/in-set? {s/Keyword s/Str :b 2} :a))
  (is (not (sut/in-set? {s/Str s/Str :b 2} :a)))
  (is (not (sut/in-set? {s/Str s/Str s/Int s/Int} :a)))
  (is (sut/in-set? {s/Keyword s/Int} :a))

  (is (not (sut/in-set? {{:a 1 :b 2} 3} :a)))
  (is (sut/in-set? {{:a 1 :b 2} 3} {:a 1 :b 2}))
  (is (sut/in-set? {{:a s/Int :b 2} 3} {:a 1 :b 2}))
  (is (sut/in-set? {{:a s/Int s/Keyword 2} 3} {:a 1 :b 2}))
  (is (not (sut/in-set? {{:a s/Int s/Str 2} 3} {:a 1 :b 2})))

  (is (sut/in-set? {{:z {:a 1 :b 2} :c 3} "x"} {:z {:a 1 :b 2} :c 3}))
  (is (sut/in-set? {{:z {:a s/Int :b 2} :c 3} "x"} {:z {:a 1 :b 2} :c 3}))
  (is (sut/in-set? {{:z {:a 1 s/Keyword s/Int} :c 3} "x"} {:z {:a 1 :b 2} :c 3}))
  (is (sut/in-set? {{:z {:a 1 :b 2} :c 3} s/Str} {:z {:a 1 :b 2} :c 3}))

  (is (sut/in-set? {{:a s/Keyword} s/Any}
                   {(schema-or-value s/Keyword :a) (schema-or-value s/Keyword :hey)}))
  (is (sut/in-set? {{:b (s/maybe s/Str)} s/Any}
                   {(schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}))
  (is (sut/in-set? {{:a s/Keyword :b (s/maybe s/Str)} s/Any}
                   {(schema-or-value s/Keyword :a) (schema-or-value s/Keyword :hey)
                    (schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}))
  (is (sut/in-set? {{:a s/Keyword :b (s/maybe s/Str) (s/optional-key :c) s/Int} s/Any}
                   {(schema-or-value s/Keyword :a) (schema-or-value s/Keyword :hey)
                    (schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}))

  ;; Current blocker: nested schema-or-values
  (is (sut/in-set? {{:a s/Keyword} s/Any}
                   (schema-or-value {(schema-or-value s/Keyword :a) (schema-or-value s/Keyword :hey)}
                                    {:a :hey})))
  (is (sut/in-set? {{:a s/Keyword :b (s/maybe s/Str) (s/optional-key :c) s/Int} s/Any}
                   (schema-or-value {(schema-or-value s/Keyword :a) (schema-or-value s/Keyword :hey)
                                     (schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}
                                    {:a :hey :b "there"})))
  )

(deftest check-keys-with-map-keys
  (is (empty? (sut/check-keys sample-ctx {s/Keyword s/Int} {:a s/Any})))
  (is (empty? (sut/check-keys sample-ctx {s/Keyword s/Int} {s/Keyword s/Any})))
  (is (empty? (sut/check-keys sample-ctx {:a s/Int} {:a s/Any})))
  (is (empty? (sut/check-keys sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) s/Any})))

  (is (empty? (sut/check-keys sample-ctx {Update [Item]} {{:a :hey :b "there"} [{:x "a" :y 2}]})))
  (is (empty? (sut/check-keys sample-ctx {Update [Item]} {{(schema-or-value s/Keyword :a) (schema-or-value s/Keyword :hey)
                                                           (schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}
                                                          [{:x "a" :y 2}]})))
  (is (empty? (sut/check-keys sample-ctx {Update [Item]} {Update [Item]})))
  (is (empty? (sut/check-keys sample-ctx {Update [Item]} {Update [s/Any]})))
  (is (empty? (sut/check-keys sample-ctx {Update [Item]} {Update s/Any})))
  (is (empty? (sut/check-keys sample-ctx {{:a s/Int} s/Str} {{:a s/Int} "hello"})))
  (is (empty? (sut/check-keys sample-ctx {{(s/optional-key :a) s/Int} s/Str} {{:a s/Int} "hello"})))
  (is (empty? (sut/check-keys sample-ctx {{(s/optional-key :a) s/Int} s/Str} {{(schema-or-value s/Keyword :a) s/Int} "hello"})))

  (is (seq (sut/check-keys sample-ctx {Update [Item]} {{:a :hey :b 3} [{:x "a" :y 2}]})))
  ;; Bad value, but right key
  (is (empty? (sut/check-keys sample-ctx {Update [Item]} {{:a :hey :b "there"} [{:x "a" :z 2}]})))
  (is (seq (sut/check-keys sample-ctx {Update [Item]} {{(schema-or-value s/Keyword :a) (schema-or-value s/Int 3)
                                                           (schema-or-value s/Keyword :b) (schema-or-value s/Str "there")}
                                                          [{:x "a" :y 2}]})))
  (is (seq (sut/check-keys sample-ctx {{:a s/Int} s/Str} {{:b s/Int} "hello"})))
  (is (seq (sut/check-keys sample-ctx {{:a s/Int} s/Str} {{(schema-or-value s/Keyword :b) s/Int} "hello"})))
  (is (seq (sut/check-keys sample-ctx {{:a s/Int} s/Str} {{:a s/Str} "hello"})))
  (is (seq (sut/check-keys sample-ctx {{:a s/Int} s/Str} {{(schema-or-value s/Keyword :a) s/Str} "hello"})))
  (is (seq (sut/check-keys sample-ctx {{(s/optional-key :a) s/Int} s/Str} {{:b s/Int} "hello"})))
  (is (seq (sut/check-keys sample-ctx {{(s/optional-key :a) s/Int} s/Str} {{:a s/Str} "hello"})))
  )

(deftest mismatched-maps-test
  (is (empty? (sut/mismatched-maps sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx {s/Keyword (s/maybe s/Int)} {(schema-or-value s/Keyword :a) (schema-or-value (s/maybe s/Int) nil)})))
  (is (empty? (sut/mismatched-maps sample-ctx {s/Keyword (s/maybe s/Int)} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx
                                           {s/Keyword s/Int s/Str s/Str}
                                           {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                            (schema-or-value s/Str "b") (schema-or-value s/Str "hello")})))

  (is (seq (sut/mismatched-maps sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value (s/maybe s/Int) nil)})))
  (is (seq (sut/mismatched-maps sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Str "hello")})))
  (is (seq (sut/mismatched-maps sample-ctx
                                        {s/Keyword s/Int s/Str s/Str}
                                        {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                         (schema-or-value s/Str "b") (schema-or-value s/Int 1)})))
  (is (seq (sut/mismatched-maps sample-ctx
                                        {s/Keyword s/Int s/Str s/Str}
                                        {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)
                                         (schema-or-value s/Str "b") (schema-or-value s/Int 2)})))

  (is (empty? (sut/mismatched-maps sample-ctx {s/Keyword s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx {:a s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx  {(s/optional-key s/Keyword) s/Int} {})))
  (is (empty? (sut/mismatched-maps sample-ctx {(s/optional-key s/Keyword) s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (empty? (sut/mismatched-maps sample-ctx {(s/optional-key :a) s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))

  (is (seq (sut/mismatched-maps sample-ctx {} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (seq (sut/mismatched-maps sample-ctx {s/Str s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))
  (is (seq (sut/mismatched-maps sample-ctx {:b s/Int} {(schema-or-value s/Keyword :a) (schema-or-value s/Int 1)})))


  )
