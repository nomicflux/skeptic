(ns skeptic.analysis.native-fns
  "Native typing for clojure.lang.Numbers static calls and selected clojure.core invokes.
  Toolchain reference (Clojure 1.11.1, tools.analyzer.jvm 1.2.3):
  - Numbers: inc/dec argc 1; add/multiply argc 2 (n-ary +/* is nested static calls);
    minus argc 1 or 2; isPos/isNeg argc 1 -> bool.
  - Invoke (native-fn-dict): (+) (+ x) (*) (* x); str; format; even? odd?; inc when not inlined.
  - Literal (+ 1 2 3) never yields top-level :invoke; test 3-arg + with ((resolve '+) 1 2 3)."
  (:require [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov])
  (:import [clojure.lang Numbers]))

(def ^:private number-type at/NumericDyn)
(def ^:private bool-type (at/->GroundT :bool 'Bool))
(def ^:private str-type (at/->GroundT :str 'Str))
(def ^:private int-type (at/->GroundT :int 'Int))
(def ^:private str-arg-type at/Dyn)

(defn static-call-native-info
  [class method arity]
  (when (= class Numbers)
    (cond
      (#{'inc 'dec} method)
      (when (= arity 1)
        {:output-type number-type :expected-argtypes [number-type]})

      (#{'add 'multiply} method)
      (when (= arity 2)
        {:output-type number-type :expected-argtypes [number-type number-type]})

      (= 'minus method)
      (cond
        (= arity 1) {:output-type number-type :expected-argtypes [number-type]}
        (= arity 2) {:output-type number-type :expected-argtypes [number-type number-type]}
        :else nil)

      (#{'isPos 'isNeg} method)
      (when (= arity 1)
        {:output-type bool-type :expected-argtypes [number-type]})

      :else nil)))

(def ^:private num-zero-method (at/->FnMethodT [] number-type 0 false []))
(def ^:private num-unary-method (at/->FnMethodT [number-type] number-type 1 false '[n]))
(def ^:private num-binary-method (at/->FnMethodT [number-type number-type] number-type 2 false '[x y]))
(def ^:private num-ternary-varargs-method
  (at/->FnMethodT [number-type number-type number-type] number-type 3 true '[x y more]))

(def ^:private num-plus-star-type
  (at/->FunT [num-zero-method num-unary-method num-binary-method num-ternary-varargs-method]))

(def ^:private inc-type
  (at/->FunT [num-unary-method]))

(def ^:private str-zero-method (at/->FnMethodT [] str-type 0 false []))
(def ^:private str-unary-method (at/->FnMethodT [str-arg-type] str-type 1 false '[s]))
(def ^:private str-varargs-method
  (at/->FnMethodT [str-arg-type str-arg-type] str-type 2 true '[s args]))

(def ^:private str-type-val
  (at/->FunT [str-zero-method str-unary-method str-varargs-method]))

(def ^:private format-unary-method (at/->FnMethodT [str-type] str-type 1 false '[fmt]))
(def ^:private format-varargs-method
  (at/->FnMethodT [str-type str-arg-type] str-type 2 true '[fmt args]))

(def ^:private format-type
  (at/->FunT [format-unary-method format-varargs-method]))

(def ^:private int-pred-method (at/->FnMethodT [int-type] bool-type 1 false '[n]))
(def ^:private int-pred-type
  (at/->FunT [int-pred-method]))

(def native-fn-dict
  {'clojure.core/+ num-plus-star-type
   'clojure.core/* num-plus-star-type
   'clojure.core/inc inc-type
   'clojure.core/str str-type-val
   'clojure.core/format format-type
   'clojure.core/even? int-pred-type
   'clojure.core/odd? int-pred-type})

(defn- native-prov
  [sym]
  (prov/->Provenance :native sym nil nil))

(def native-fn-provenance
  (into {} (map (fn [sym] [sym (native-prov sym)])) (keys native-fn-dict)))
