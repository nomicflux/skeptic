(ns skeptic.test-examples.collections
  (:require [schema.core :as s]
            [skeptic.test-examples.basics :refer [int-add]]
            [skeptic.test-examples.contracts :as contracts]))

(s/defschema NestedNameDesc
  {:user {:name s/Str}})

(s/defschema IntPair
  [(s/one s/Int 'a) (s/one s/Int 'b)])

(s/defschema IntTriple
  [(s/one s/Int 'a) (s/one s/Int 'b) (s/one s/Int 'c)])

(s/defschema IntQuad
  [(s/one s/Int 'a) (s/one s/Int 'b) (s/one s/Int 'c) (s/one s/Int 'd)])

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

(s/defn bad-int-pair-helper :- [(s/one s/Int 'a) (s/one s/Str 'b)]
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

(def one-prefixed-open-id "x")

(s/defn one-prefixed-open-consumer
  [l :- [(s/one s/Any "a")
         (s/one s/Any "b")
         s/Any]]
  nil)

(s/defn one-prefixed-open-success
  []
  (one-prefixed-open-consumer [one-prefixed-open-id "y" :k3 :k4 :k]))

(s/defschema IntStrThenKws
  [(s/one s/Int 'a) (s/one s/Str 'b) s/Keyword])

(s/defn takes-int-str-then-kws :- s/Keyword
  [xs :- IntStrThenKws]
  :ok)

(defn open-tail-success-extras    [] (takes-int-str-then-kws [1 "x" :k :j :l]))
(defn open-tail-success-no-extras [] (takes-int-str-then-kws [1 "x"]))
(defn open-tail-failure-prefix    [] (takes-int-str-then-kws ["x" 1 :k]))
(defn open-tail-failure-tail-elem [] (takes-int-str-then-kws [1 "x" 99]))

(s/defschema FourFixed
  [(s/one s/Int 'a) (s/one s/Str 'b)
   (s/one s/Keyword 'c) (s/one s/Keyword 'd)])

(s/defn takes-four-fixed :- s/Keyword
  [xs :- FourFixed]
  :ok)

(defn closed-prefix-success  [] (takes-four-fixed [1 "x" :k :j]))
(defn closed-prefix-too-long [] (takes-four-fixed [1 "x" :k :j :extra]))

(s/defschema IntThenStrOrKw
  [(s/one s/Int 'a) (s/cond-pre s/Str s/Keyword)])

(s/defn takes-int-then-str-or-kw :- s/Keyword
  [xs :- IntThenStrOrKw]
  :ok)

(defn cond-pre-tail-success [] (takes-int-then-str-or-kw [1 "x" :k "y"]))
(defn cond-pre-tail-failure [] (takes-int-then-str-or-kw [1 99]))

(defn homogeneous-tail-element-failure [] (takes-int-vec [1 "two" 3]))

(s/defn takes-empty :- s/Keyword [xs :- []] :ok)
(defn empty-closed-success    [] (takes-empty []))
(defn empty-closed-too-long   [] (takes-empty [1]))

(s/defschema IntThenMaybeStr
  [(s/one s/Int 'a) (s/maybe s/Str)])

(s/defn takes-int-then-maybe-str :- s/Keyword
  [xs :- IntThenMaybeStr]
  :ok)

(defn maybe-tail-success-strs    [] (takes-int-then-maybe-str [1 "x" "y"]))
(defn maybe-tail-failure-int     [] (takes-int-then-maybe-str [1 99]))
