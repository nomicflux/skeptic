(ns skeptic.analysis.native-fns
  "Native typing for clojure.lang.Numbers static calls and selected clojure.core invokes.
  Toolchain reference (Clojure 1.11.1, tools.analyzer.jvm 1.2.3):
  - Numbers: inc/dec argc 1; add/multiply argc 2 (n-ary +/* is nested static calls);
    minus argc 1 or 2; isPos/isNeg argc 1 -> bool.
  - Invoke (native-fn-dict): (+) (+ x) (*) (* x); str; format; even? odd?; inc when not inlined.
  - Literal (+ 1 2 3) never yields top-level :invoke; test 3-arg + with ((resolve '+) 1 2 3)."
  (:require [skeptic.analysis.types :as at])
  (:import [clojure.lang Numbers]))

(def ^:private number-type (at/->GroundT {:class java.lang.Number} 'Number))
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

(def native-fn-dict
  {'clojure.core/+ {:name 'clojure.core/+
                    :output-type number-type
                    :arglists {0 {:arglist '[]
                                  :types []
                                  :count 0}
                               1 num-unary
                               2 num-binary
                               :varargs num-ternary-varargs}}
   'clojure.core/* {:name 'clojure.core/*
                    :output-type number-type
                    :arglists {0 {:arglist '[]
                                  :types []
                                  :count 0}
                               1 num-unary
                               2 num-binary
                               :varargs num-ternary-varargs}}
   'clojure.core/inc {:name 'clojure.core/inc
                       :output-type number-type
                       :arglists {1 num-unary}}
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
                                                   (tp 'y str-arg-type)]}}}
   'clojure.core/format {:name 'clojure.core/format
                         :output-type str-type
                         :arglists {1 {:arglist '[fmt]
                                        :types [(tp 'fmt str-type)]
                                        :count 1}
                                    :varargs {:arglist '[fmt x]
                                              :count 2
                                              :types [(tp 'fmt str-type)
                                                      (tp 'x str-arg-type)]}}}
   'clojure.core/even? {:name 'clojure.core/even?
                        :output-type bool-type
                        :arglists {1 {:arglist '[x]
                                      :types [(tp 'x int-type)]
                                      :count 1}}}
   'clojure.core/odd? {:name 'clojure.core/odd?
                       :output-type bool-type
                       :arglists {1 {:arglist '[x]
                                     :types [(tp 'x int-type)]
                                     :count 1}}}})
