(ns skeptic.test-examples.collections
  (:require [schema.core :as s]
            [skeptic.test-examples.basics :refer [int-add]]
            [skeptic.test-examples.contracts :as contracts]))

(s/defschema NestedNameDesc
  {:user {:name s/Str}})

(s/defschema IntPair
  [s/Int s/Int])

(s/defschema IntTriple
  [s/Int s/Int s/Int])

(s/defschema IntQuad
  [s/Int s/Int s/Int s/Int])

(s/defn takes-nested-name :- s/Keyword
  [x :- NestedNameDesc]
  :ok)

(defn nested-map-input-failure
  []
  (takes-nested-name {:user {:name :bad}}))

(defn nested-map-input-success
  []
  (takes-nested-name {:user {:name "ok"}}))

(s/defn takes-int-pair :- s/Keyword
  [xs :- IntPair]
  :ok)

(s/defn takes-int-vec :- s/Keyword
  [xs :- [s/Int]]
  :ok)

(s/defn takes-int-triple :- s/Keyword
  [xs :- IntTriple]
  :ok)

(s/defn takes-int-quad :- s/Keyword
  [xs :- IntQuad]
  :ok)

(s/defn bad-int-pair-helper :- [s/Int s/Str]
  []
  [1 "oops"])

(defn vector-input-failure
  []
  (takes-int-pair (bad-int-pair-helper)))

(defn vector-input-success
  []
  (takes-int-pair [1 2]))

(defn vector-triple-to-homogeneous-success
  [x y z]
  (takes-int-vec [x y z]))

(defn vector-triple-to-fixed-success
  [x y z]
  (takes-int-triple [x y z]))

(defn vector-triple-to-pair-failure
  [x y z]
  (takes-int-pair [x y z]))

(defn vector-triple-to-quad-failure
  [x y z]
  (takes-int-quad [x y z]))

(s/defn map-literal-input-success :- s/Int
  []
  (int-add (:a {:a 1}) 0))

(s/defn map-var-input-success :- s/Int
  [m :- {:a s/Int}]
  (int-add (:a m) 0))

(defn map-unannotated-fn-input-success
  [_m]
  (int-add 1 1))

(s/defn simple-map-output-success :- {:a s/Int}
  []
  {:a 1})

(s/defn vec-literal-input-success :- s/Int
  []
  (int-add (first [1 2]) 0))

(defn format-hello-map-success
  []
  (format "Hello %s" {:a 1 :b 2}))

(s/defn a-dissoc :- [{:a s/Int}]
  []
  (let [base {:a 1 :b 2 :c 3 :d 4 :e 5}]
    [(dissoc base :b :c :d :e)]))

(s/defn abcde-maps :- [{:a s/Int :b s/Int :c s/Int :d s/Int :e s/Int}]
  []
  (let [base {:a 1}]
    [(assoc base :b 2 :c 3 :d 4 :e 5)]))

(s/defn abcde-maps-bad :- [{:a s/Int :b s/Int :c s/Int :d s/Int :e s/Int}]
  []
  (let [base {:a 1}]
    [(assoc base :b 2 :c 3 :d 4 :e "oops")]))

(s/defn field-in-thread-success :- [s/Int]
  [items :- [contracts/HasA]]
  (->> items
       (map :a)
       (mapcat (fn [n] [n (dec n)]))))

(s/defschema Source
  {(s/optional-key :n) (s/named (s/maybe s/Int) 'MaybeInt)})

(s/defschema Target
  {:n (s/maybe s/Int)})

(s/defn maybe-target-success :- Target
  [{:keys [n]} :- Source]
  {:n n})
