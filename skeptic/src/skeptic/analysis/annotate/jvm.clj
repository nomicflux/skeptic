(ns skeptic.analysis.annotate.jvm
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.map-projection :as map-projection]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.annotate.shared-call :as shared-call]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.native-fns :as native-fns]
            [skeptic.analysis.type-ops :as ato]))

(s/defn annotate-instance-call :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [instance ((:recurse ctx) ctx (:instance node))
        args (mapv #((:recurse ctx) ctx %) (:args node))
        type (when (= 'nth (:method node))
               (coll/instance-nth-element-type (:type instance) (first args)))]
    (assoc node :instance instance :args args :type (or type (aapi/dyn ctx)))))

(defn- shared-static-output-type
  [ctx node args default-output-type]
  (cond
    (ac/static-get-call? node) (shared-call/shared-call-output-type ctx :get args default-output-type)
    (ac/static-merge-call? node) (shared-call/shared-call-output-type ctx :merge args default-output-type)
    (and (ac/static-assoc-call? node) (>= (count args) 3))
    (shared-call/shared-call-output-type ctx :assoc args default-output-type)
    (and (ac/static-dissoc-call? node) (>= (count args) 2))
    (shared-call/shared-call-output-type ctx :dissoc args default-output-type)
    (and (ac/static-update-call? node) (>= (count args) 3))
    (shared-call/shared-call-output-type ctx :update args default-output-type)
    (ac/static-contains-call? node) (shared-call/shared-call-output-type ctx :contains args default-output-type)
    (and (= 'seq (:method node)) (= 1 (count args)))
    (shared-call/shared-call-output-type ctx :seq args default-output-type)
    :else nil))

(defn- static-native-output-type
  [ctx node args actual-argtypes default-output-type native-info]
  (if native-info
    (numeric/narrow-static-numbers-output (ato/derive-prov default-output-type)
                                          node args actual-argtypes native-info)
    (aapi/dyn ctx)))

(s/defn annotate-static-call :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))
        actual-argtypes (mapv :type args)
        native-info (native-fns/static-call-native-info (:class node) (:method node) (count args))
        default-output-type (or (some-> native-info :output-type)
                                (some-> args first :type)
                                (aapi/dyn ctx))
        expected-argtypes (or (:expected-argtypes native-info)
                              (vec (repeat (count args) (aapi/dyn ctx))))
        type (or (shared-static-output-type ctx node args default-output-type)
                 (static-native-output-type ctx node args actual-argtypes default-output-type native-info))
        origin (when (and (ac/static-get-call? node)
                          (<= 2 (count args) 3))
                 (map-projection/map-key-lookup-origin
                  ctx (first args)
                  (ac/get-key-query ctx (second args))
                  (if (= 3 (count args)) (:type (nth args 2)) amo/no-default)))]
    (cond-> (assoc node
                   :args args
                   :actual-argtypes actual-argtypes
                   :expected-argtypes expected-argtypes
                   :type type)
      origin
      (assoc :origin origin))))
