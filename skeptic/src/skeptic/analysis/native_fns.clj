(ns skeptic.analysis.native-fns
  "Native typing for clojure.lang.Numbers static calls and selected clojure.core invokes.
  Toolchain reference (Clojure 1.11.1, tools.analyzer.jvm 1.2.3):
  - Numbers: inc/dec argc 1; add/multiply argc 2 (n-ary +/* is nested static calls);
    minus argc 1 or 2; isPos/isNeg argc 1 -> bool.
  - Invoke (native-fn-dict): (+) (+ x) (*) (* x); str; format; even? odd?; inc when not inlined.
  - Literal (+ 1 2 3) never yields top-level :invoke; test 3-arg + with ((resolve '+) 1 2 3)."
  (:require [skeptic.analysis.types :as at])
  (:import [clojure.lang Numbers]))

(def ^:private number-type at/NumericDyn)
(def ^:private bool-type (at/->GroundT :bool 'Bool))
(def ^:private str-type (at/->GroundT :str 'Str))
(def ^:private int-type (at/->GroundT :int 'Int))
(def ^:private str-arg-type at/Dyn)

(defn- tp [sym t]
  {:name sym :type t :optional? false})

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

(def ^:private num-unary
  {:arglist '[x]
   :types [(tp 'x number-type)]
   :count 1})

(def ^:private num-binary
  {:arglist '[x y]
   :types [(tp 'x number-type) (tp 'y number-type)]
   :count 2})

(def ^:private num-ternary-varargs
  {:arglist '[x y more]
   :count 3
   :types [(tp 'x number-type) (tp 'y number-type) (tp 'more number-type)]})

(def ^:private num-zero-method (at/->FnMethodT [] number-type 0 false))
(def ^:private num-unary-method (at/->FnMethodT [number-type] number-type 1 false))
(def ^:private num-binary-method (at/->FnMethodT [number-type number-type] number-type 2 false))
(def ^:private num-ternary-varargs-method
  (at/->FnMethodT [number-type number-type number-type] number-type 3 true))

(def ^:private num-plus-star-typings
  [(at/->FunT [num-zero-method num-unary-method num-binary-method num-ternary-varargs-method])])

(def ^:private inc-typings
  [(at/->FunT [num-unary-method])])

(def ^:private str-zero-method (at/->FnMethodT [] str-type 0 false))
(def ^:private str-unary-method (at/->FnMethodT [str-arg-type] str-type 1 false))
(def ^:private str-varargs-method
  (at/->FnMethodT [str-arg-type str-arg-type] str-type 2 true))

(def ^:private str-typings
  [(at/->FunT [str-zero-method str-unary-method str-varargs-method])])

(def ^:private format-unary-method (at/->FnMethodT [str-type] str-type 1 false))
(def ^:private format-varargs-method
  (at/->FnMethodT [str-type str-arg-type] str-type 2 true))

(def ^:private format-typings
  [(at/->FunT [format-unary-method format-varargs-method])])

(def ^:private int-pred-method (at/->FnMethodT [int-type] bool-type 1 false))
(def ^:private int-pred-typings
  [(at/->FunT [int-pred-method])])

(def native-fn-dict
  {'clojure.core/+ {:name 'clojure.core/+
                    :output-type number-type
                    :arglists {0 {:arglist '[]
                                  :types []
                                  :count 0}
                               1 num-unary
                               2 num-binary
                               :varargs num-ternary-varargs}
                    :typings num-plus-star-typings}
   'clojure.core/* {:name 'clojure.core/*
                    :output-type number-type
                    :arglists {0 {:arglist '[]
                                  :types []
                                  :count 0}
                               1 num-unary
                               2 num-binary
                               :varargs num-ternary-varargs}
                    :typings num-plus-star-typings}
   'clojure.core/inc {:name 'clojure.core/inc
                      :output-type number-type
                      :arglists {1 num-unary}
                      :typings inc-typings}
   'clojure.core/str {:name 'clojure.core/str
                      :output-type str-type
                      :arglists {0 {:arglist '[]
                                    :types []
                                    :count 0}
                                 1 {:arglist '[x]
                                    :types [(tp 'x str-arg-type)]
                                    :count 1}
                                 :varargs {:arglist '[x y]
                                           :count 2
                                           :types [(tp 'x str-arg-type)
                                                   (tp 'y str-arg-type)]}}
                      :typings str-typings}
   'clojure.core/format {:name 'clojure.core/format
                         :output-type str-type
                         :arglists {1 {:arglist '[fmt]
                                       :types [(tp 'fmt str-type)]
                                       :count 1}
                                    :varargs {:arglist '[fmt x]
                                              :count 2
                                              :types [(tp 'fmt str-type)
                                                      (tp 'x str-arg-type)]}}
                         :typings format-typings}
   'clojure.core/even? {:name 'clojure.core/even?
                        :output-type bool-type
                        :arglists {1 {:arglist '[x]
                                      :types [(tp 'x int-type)]
                                      :count 1}}
                        :typings int-pred-typings}
   'clojure.core/odd? {:name 'clojure.core/odd?
                       :output-type bool-type
                       :arglists {1 {:arglist '[x]
                                     :types [(tp 'x int-type)]
                                     :count 1}}
                       :typings int-pred-typings}})
