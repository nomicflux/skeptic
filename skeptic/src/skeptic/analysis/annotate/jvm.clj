(ns skeptic.analysis.annotate.jvm
  (:require [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.annotate.shared-call :as aasc]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.native-fns :as anf]
            [skeptic.analysis.types :as at]))

(defn annotate-instance-call
  [ctx node]
  (let [instance ((:recurse ctx) ctx (:instance node))
        args (mapv #((:recurse ctx) ctx %) (:args node))
        method (:method node)
        it (:type instance)
        output (when (#{'nth} method)
                 (aac/instance-nth-element-type it (first args)))]
    (assoc node
           :instance instance
           :args args
           :type (or output at/Dyn))))

(defn- shared-static-output-type
  [node args default-output-type]
  (cond
    (ac/static-get-call? node)
    (aasc/shared-call-output-type :get args default-output-type)

    (ac/static-merge-call? node)
    (aasc/shared-call-output-type :merge args default-output-type)

    (and (ac/static-assoc-call? node)
         (>= (count args) 3))
    (aasc/shared-call-output-type :assoc args default-output-type)

    (and (ac/static-dissoc-call? node)
         (>= (count args) 2))
    (aasc/shared-call-output-type :dissoc args default-output-type)

    (and (ac/static-update-call? node)
         (>= (count args) 3))
    (aasc/shared-call-output-type :update args default-output-type)

    (ac/static-contains-call? node)
    (aasc/shared-call-output-type :contains args default-output-type)

    (and (ac/seq-call? node)
         (= 1 (count args)))
    (aasc/shared-call-output-type :seq args default-output-type)

    :else nil))

(defn- static-native-output-type
  [node args actual-argtypes native-info]
  (cond
    native-info
    (aan/narrow-static-numbers-output node args actual-argtypes native-info)

    :else at/Dyn))

(defn annotate-static-call
  [ctx node]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))
        actual-argtypes (mapv :type args)
        native-info (anf/static-call-native-info (:class node) (:method node) (count args))
        default-expected (vec (repeat (count args) at/Dyn))
        default-output-type (or (some-> native-info :output-type)
                                (some-> args first :type)
                                at/Dyn)
        expected-argtypes (if native-info
                            (:expected-argtypes native-info)
                            default-expected)
        type (or (shared-static-output-type node args default-output-type)
                 (static-native-output-type node args actual-argtypes native-info))]
    (assoc node
           :args args
           :actual-argtypes actual-argtypes
           :expected-argtypes expected-argtypes
           :type type)))
