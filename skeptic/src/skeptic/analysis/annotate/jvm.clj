(ns skeptic.analysis.annotate.jvm
  (:require [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.annotate.map-path :as aamp]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.map-ops.algebra :as amoa]
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

(defn- static-type-get-or-merge
  [node args]
  (cond
    (ac/static-get-call? node)
    (let [[target key-node default-node] args
          key-type (ac/get-key-query key-node)]
      (if default-node
        (amo/map-get-type (:type target)
                          key-type
                          (:type default-node))
        (amo/map-get-type (:type target)
                          key-type)))

    (ac/static-merge-call? node)
    (amoa/merge-types (map :type args))

    :else nil))

(defn- static-type-assoc-dissoc-update
  [node args]
  (cond
    (and (ac/static-assoc-call? node)
         (>= (count args) 3))
    (let [[m & kvs] args]
      (aamp/reduce-assoc-pairs (:type m) (partition 2 kvs)))

    (and (ac/static-dissoc-call? node)
         (>= (count args) 2))
    (let [[m & ks] args]
      (aamp/reduce-dissoc-keys (:type m) ks))

    (and (ac/static-update-call? node)
         (>= (count args) 3))
    (let [[m kn uf] args
          lk (when (ac/literal-map-key? kn)
               (ac/literal-node-value kn))]
      (if (keyword? lk)
        (amoa/update-type (:type m) lk (:type uf))
        at/Dyn))

    :else nil))

(defn- static-type-contains-seq-native
  [node args actual-argtypes native-info]
  (cond
    (ac/static-contains-call? node)
    aan/bool-type

    (and (ac/seq-call? node)
         (= 1 (count args)))
    (let [t (:type (first args))]
      (or (cond
            (at/seq-type? t) t
            (at/vector-type? t) (aac/vector-to-homogeneous-seq-type t)
            :else nil)
          at/Dyn))

    native-info
    (aan/narrow-static-numbers-output node args actual-argtypes native-info)

    :else at/Dyn))

(defn annotate-static-call
  [ctx node]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))
        actual-argtypes (mapv :type args)
        native-info (anf/static-call-native-info (:class node) (:method node) (count args))
        default-expected (vec (repeat (count args) at/Dyn))
        expected-argtypes (if native-info
                            (:expected-argtypes native-info)
                            default-expected)
        type (or (static-type-get-or-merge node args)
                 (static-type-assoc-dissoc-update node args)
                 (static-type-contains-seq-native node args actual-argtypes native-info))]
    (assoc node
           :args args
           :actual-argtypes actual-argtypes
           :expected-argtypes expected-argtypes
           :type type)))
