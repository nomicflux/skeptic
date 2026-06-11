(ns skeptic.analysis.predicates
  "Shared registry of clojure.core predicates that Skeptic can type.
  Two consumers:
    - native_fns: each predicate is typed Dyn -> Bool when invoked
    - bridge admission: (s/pred f) and Malli's bare-predicate-as-schema
      both convert to the predicate's witness type."
  (:require [clojure.string :as str]
            [schema.core :as s]
            [skeptic.analysis.types :as at]
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
   'clojure.core/nil?     (fn [p] (ato/exact-value-type p nil))
   'clojure.core/map?     (fn [p] (at/->MapT p {(at/Dyn p) (at/Dyn p)}))})

(def predicate-symbols (set (keys witness-builders)))

(def ^:private bare->qualified
  (into {} (map (fn [q] [(symbol (name q)) q])) predicate-symbols))

(s/defn demunged-predicate-symbol :- s/Symbol
  "Strip the Clojure compiler's __NNNN class-name counter that
   schema.utils/fn-name leaves on direct-linked predicate names
   (map?--4367 -> map?)."
  [sym :- s/Symbol]
  (symbol (namespace sym) (str/replace (name sym) #"--\d+$" "")))

(s/defn resolve-predicate-symbol :- (s/maybe s/Symbol)
  [sym :- s/Symbol]
  (let [sym (demunged-predicate-symbol sym)]
    (cond
      (contains? witness-builders sym) sym
      (contains? bare->qualified sym) (get bare->qualified sym)
      :else nil)))

(defn predicate?
  [sym]
  (some? (resolve-predicate-symbol sym)))

(defn- native-prov
  [sym lang]
  (prov/make-provenance :native sym nil nil [] lang))

(s/defn predicate-fn-type :- at/SemanticType
  [qualified-sym :- s/Symbol
   lang          :- provs/Lang]
  (let [p (native-prov qualified-sym lang)
        bool-t (at/->GroundT p :bool 'Bool)
        method (at/->FnMethodT p [(at/Dyn p)] bool-t 1 false '[x])]
    (at/->FunT p [method])))

(s/defn witness-type :- at/SemanticType
  [qualified-sym :- s/Symbol
   prov          :- provs/Provenance]
  ((get witness-builders qualified-sym) prov))

(def cljs-predicate-symbols
  (into #{} (map (fn [q] (symbol "cljs.core" (name q)))) predicate-symbols))

(defn predicate-fn-entries
  []
  (map (juxt identity #(predicate-fn-type % :clj)) predicate-symbols))

(defn cljs-predicate-fn-entries
  []
  (map (juxt identity #(predicate-fn-type % :cljs)) cljs-predicate-symbols))
