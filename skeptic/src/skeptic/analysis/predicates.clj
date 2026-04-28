(ns skeptic.analysis.predicates
  "Shared registry of clojure.core predicates that Skeptic can type.
  Two consumers:
    - native_fns: each predicate is typed Dyn -> Bool when invoked
    - bridge admission: (s/pred f) and Malli's bare-predicate-as-schema
      both convert to the predicate's witness type."
  (:require [schema.core :as s]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.provenance :as prov]
            [skeptic.provenance.schema :as provs]))

(def ^:private witness-builders
  {'clojure.core/string?  (fn [p] (at/->GroundT p :str 'Str))
   'clojure.core/keyword? (fn [p] (at/->GroundT p :keyword 'Keyword))
   'clojure.core/integer? (fn [p] (at/->GroundT p :int 'Int))
   'clojure.core/int?     (fn [p] (at/->GroundT p :int 'Int))
   'clojure.core/number?  (fn [p] (at/NumericDyn p))
   'clojure.core/pos?     (fn [p] (at/NumericDyn p))
   'clojure.core/neg?     (fn [p] (at/NumericDyn p))
   'clojure.core/boolean? (fn [p] (at/->GroundT p :bool 'Bool))
   'clojure.core/nil?     (fn [p] (ato/exact-value-type p nil))})

(def predicate-symbols (set (keys witness-builders)))

(def ^:private bare->qualified
  (into {} (map (fn [q] [(symbol (name q)) q])) predicate-symbols))

(defn resolve-predicate-symbol
  [sym]
  (cond
    (contains? witness-builders sym) sym
    (contains? bare->qualified sym) (get bare->qualified sym)
    :else nil))

(defn predicate?
  [sym]
  (some? (resolve-predicate-symbol sym)))

(defn- native-prov [sym]
  (prov/make-provenance :native sym nil nil))

(s/defn predicate-fn-type :- ats/SemanticType
  [qualified-sym :- s/Symbol]
  (let [p (native-prov qualified-sym)
        bool-t (at/->GroundT p :bool 'Bool)
        method (at/->FnMethodT p [(at/Dyn p)] bool-t 1 false '[x])]
    (at/->FunT p [method])))

(s/defn witness-type :- ats/SemanticType
  [qualified-sym :- s/Symbol
   prov          :- provs/Provenance]
  ((get witness-builders qualified-sym) prov))

(defn predicate-fn-entries
  []
  (map (juxt identity predicate-fn-type) predicate-symbols))
