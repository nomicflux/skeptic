(ns skeptic.analysis.native-fns
  (:require [skeptic.analysis.types :as at])
  (:import [clojure.lang Numbers]))

(def ^:private int-type (at/->GroundT :int 'Int))

(defn static-call-native-info
  "JVM analyzer emits `inc` as :static-call on Numbers, not :invoke."
  [class method arity]
  (when (and (= class Numbers)
             (= method 'inc)
             (= arity 1))
    {:output-type int-type
     :expected-argtypes [int-type]}))

(def native-fn-dict
  {'clojure.core/inc {:name 'clojure.core/inc
                      :output-type int-type
                      :arglists {1 {:arglist '[n]
                                    :types [{:name 'n :type int-type :optional? false}]
                                    :count 1}}}})
