(ns skeptic.analysis.annotate.jvm
  (:require [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.map-projection :as map-projection]
            [skeptic.analysis.annotate.shared-call :as shared-call]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.native-fns :as native-fns]
            [skeptic.analysis.types :as at]))

(defn annotate-instance-call
  [ctx node]
  (let [instance ((:recurse ctx) ctx (:instance node))
        args (mapv #((:recurse ctx) ctx %) (:args node))
        type (when (= 'nth (:method node))
               (coll/instance-nth-element-type (:type instance) (first args)))]
    (assoc node :instance instance :args args :type (or type at/Dyn))))

(defn- shared-static-output-type
  [node args default-output-type]
  (cond
    (ac/static-get-call? node) (shared-call/shared-call-output-type :get args default-output-type)
    (ac/static-merge-call? node) (shared-call/shared-call-output-type :merge args default-output-type)
    (and (ac/static-assoc-call? node) (>= (count args) 3))
    (shared-call/shared-call-output-type :assoc args default-output-type)
    (and (ac/static-dissoc-call? node) (>= (count args) 2))
    (shared-call/shared-call-output-type :dissoc args default-output-type)
    (and (ac/static-update-call? node) (>= (count args) 3))
    (shared-call/shared-call-output-type :update args default-output-type)
    (ac/static-contains-call? node) (shared-call/shared-call-output-type :contains args default-output-type)
    (and (= 'seq (:method node)) (= 1 (count args)))
    (shared-call/shared-call-output-type :seq args default-output-type)
    :else nil))

(defn- static-native-output-type
  [node args actual-argtypes native-info]
  (if native-info
    (numeric/narrow-static-numbers-output node args actual-argtypes native-info)
    at/Dyn))

(defn annotate-static-call
  [ctx node]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))
        actual-argtypes (mapv :type args)
        native-info (native-fns/static-call-native-info (:class node) (:method node) (count args))
        default-output-type (or (some-> native-info :output-type)
                                (some-> args first :type)
                                at/Dyn)
        expected-argtypes (or (:expected-argtypes native-info)
                              (vec (repeat (count args) at/Dyn)))
        type (or (shared-static-output-type node args default-output-type)
                 (static-native-output-type node args actual-argtypes native-info))
        origin (when (and (ac/static-get-call? node)
                          (= 2 (count args)))
                 (map-projection/map-key-lookup-origin (first args)
                                                       (ac/get-key-query (second args))))]
    (cond-> (assoc node
                   :args args
                   :actual-argtypes actual-argtypes
                   :expected-argtypes expected-argtypes
                   :type type)
      origin
      (assoc :origin origin))))
