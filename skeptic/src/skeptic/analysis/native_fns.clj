(ns skeptic.analysis.native-fns
  "Native typing for clojure.lang.Numbers static calls and selected clojure.core invokes.
  Toolchain reference (Clojure 1.11.1, tools.analyzer.jvm 1.2.3):
  - Numbers: inc/dec argc 1; add/multiply argc 2 (n-ary +/* is nested static calls);
    minus argc 1 or 2; isPos/isNeg argc 1 -> bool.
  - Invoke (native-fn-dict): (+) (+ x) (*) (* x); str; format; even? odd?; inc when not inlined.
  - Literal (+ 1 2 3) never yields top-level :invoke; test 3-arg + with ((resolve '+) 1 2 3)."
  (:require [schema.core :as s]
            [skeptic.analysis.predicates :as predicates]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov])
  (:import [clojure.lang Numbers]))

(defn- native-prov
  [sym]
  (prov/make-provenance :native sym nil nil))

(defn- numbers-prov
  [method]
  (native-prov (symbol (str "clojure.lang.Numbers/" method))))

(s/defn static-call-native-info :- (s/maybe {s/Keyword s/Any})
  [class :- s/Any method :- s/Any arity :- s/Int]
  (when (= class Numbers)
    (let [p (numbers-prov method)
          n (at/NumericDyn p)
          b (at/->GroundT p :bool 'Bool)]
      (cond
        (#{'inc 'dec} method)
        (when (= arity 1)
          {:output-type n :expected-argtypes [n]})

        (#{'add 'multiply} method)
        (when (= arity 2)
          {:output-type n :expected-argtypes [n n]})

        (= 'minus method)
        (cond
          (= arity 1) {:output-type n :expected-argtypes [n]}
          (= arity 2) {:output-type n :expected-argtypes [n n]}
          :else nil)

        (#{'isPos 'isNeg} method)
        (when (= arity 1)
          {:output-type b :expected-argtypes [n]})

        :else nil))))

(defn- plus-star-type-for
  [sym]
  (let [p (native-prov sym)
        n (at/NumericDyn p)
        zero (at/->FnMethodT p [] n 0 false [])
        unary (at/->FnMethodT p [n] n 1 false '[n])
        binary (at/->FnMethodT p [n n] n 2 false '[x y])
        variadic (at/->FnMethodT p [n n n] n 3 true '[x y more])]
    (at/->FunT p [zero unary binary variadic])))

(defn- inc-type-for
  [sym]
  (let [p (native-prov sym)
        n (at/NumericDyn p)
        unary (at/->FnMethodT p [n] n 1 false '[n])]
    (at/->FunT p [unary])))

(defn- str-type-for
  [sym]
  (let [p (native-prov sym)
        str-t (at/->GroundT p :str 'Str)
        str-arg (at/Dyn p)
        zero (at/->FnMethodT p [] str-t 0 false [])
        unary (at/->FnMethodT p [str-arg] str-t 1 false '[s])
        variadic (at/->FnMethodT p [str-arg str-arg] str-t 2 true '[s args])]
    (at/->FunT p [zero unary variadic])))

(defn- format-type-for
  [sym]
  (let [p (native-prov sym)
        str-t (at/->GroundT p :str 'Str)
        str-arg (at/Dyn p)
        unary (at/->FnMethodT p [str-t] str-t 1 false '[fmt])
        variadic (at/->FnMethodT p [str-t str-arg] str-t 2 true '[fmt args])]
    (at/->FunT p [unary variadic])))

(defn- int-pred-type-for
  [sym]
  (let [p (native-prov sym)
        int-t (at/->GroundT p :int 'Int)
        bool-t (at/->GroundT p :bool 'Bool)
        method (at/->FnMethodT p [int-t] bool-t 1 false '[n])]
    (at/->FunT p [method])))

(def native-fn-dict
  (into {'clojure.core/+ (plus-star-type-for 'clojure.core/+)
         'clojure.core/* (plus-star-type-for 'clojure.core/*)
         'clojure.core/inc (inc-type-for 'clojure.core/inc)
         'clojure.core/str (str-type-for 'clojure.core/str)
         'clojure.core/format (format-type-for 'clojure.core/format)
         'clojure.core/even? (int-pred-type-for 'clojure.core/even?)
         'clojure.core/odd? (int-pred-type-for 'clojure.core/odd?)}
        (predicates/predicate-fn-entries)))

(def native-fn-provenance
  (into {} (map (fn [sym] [sym (native-prov sym)])) (keys native-fn-dict)))
