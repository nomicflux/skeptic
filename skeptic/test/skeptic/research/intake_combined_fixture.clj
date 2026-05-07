(ns skeptic.research.intake-combined-fixture
  "Cross-stream fixture: forms from BOTH libraries in one ns, with aliasing
   variants. Read by intake_data_dump_test."
  (:require [schema.core :as s]
            [malli.core :as m]
            [malli.experimental :as mx]))

;; --- Plumatic, aliased s/ ---

(s/defn aliased-defn :- s/Int
  [x :- s/Str]
  (count x))

(s/def aliased-def :- s/Int 7)

(s/defschema AliasedSchema {:a s/Int :b s/Str})

(s/defrecord AliasedRecord [a :- s/Int b :- s/Str])

(s/defprotocol AliasedProtocol
  (aliased-method :- s/Int [this x :- s/Str]))

;; --- Plumatic, fully-qualified ---

(schema.core/defn qualified-defn :- s/Int
  [x :- s/Str]
  (count x))

;; --- Plumatic, with another alias name ---

(require '[schema.core :as schemy])

(schemy/defn schemy-defn :- s/Int
  [x :- s/Str]
  (count x))

;; --- Plain Clojure (should NOT be Plumatic) ---

(defn plain-defn [x] (inc x))

;; --- Malli m/=> ---

(defn malli-arrow [x] (inc x))
(m/=> malli-arrow [:=> [:cat :int] :int])

;; --- Malli mx/defn ---

(mx/defn malli-mx :- :int
  [x :- :int]
  (inc x))

;; --- Malli :malli/schema Var-meta only ---

(defn malli-meta-only
  {:malli/schema [:=> [:cat :int] :int]}
  [x] (inc x))

;; --- Cross-stream: same Var declared via BOTH libraries ---

(s/defn cross-stream :- s/Int
  [x :- s/Int]
  (inc x))

(m/=> cross-stream [:=> [:cat :int] :int])

;; --- do-wrapped (should be skipped by source-form scan) ---

(do
  (s/defn do-wrapped-defn :- s/Int [x :- s/Int] (inc x)))
