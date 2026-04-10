(ns skeptic.analysis.annotate.shared-call
  (:require [skeptic.analysis.annotate.coll :as aac]
            [skeptic.analysis.annotate.map-path :as aamp]
            [skeptic.analysis.annotate.numeric :as aan]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.types :as at]))

(defn- shared-get-output-type
  [args]
  (let [[target key-node default-node] args
        key-type (ac/get-key-query key-node)]
    (if default-node
      (amo/map-get-type (:type target)
                        key-type
                        (:type default-node))
      (amo/map-get-type (:type target)
                        key-type))))

(defn- shared-merge-output-type
  [args]
  (amoa/merge-types (map :type args)))

(defn- shared-assoc-output-type
  [args]
  (let [[m & kvs] args]
    (aamp/reduce-assoc-pairs (:type m) (partition 2 kvs))))

(defn- shared-dissoc-output-type
  [args]
  (let [[m & ks] args]
    (aamp/reduce-dissoc-keys (:type m) ks)))

(defn- shared-update-output-type
  [args default-output-type]
  (let [[m kn uf] args
        lk (when (ac/literal-map-key? kn)
             (ac/literal-node-value kn))]
    (if (keyword? lk)
      (amoa/update-type (:type m) lk (:type uf))
      default-output-type)))

(defn- shared-seq-output-type
  [args]
  (let [t (:type (first args))]
    (or (cond
          (at/seq-type? t) t
          (at/vector-type? t) (aac/vector-to-homogeneous-seq-type t)
          :else nil)
        at/Dyn)))

(defn shared-call-output-type
  [shared-op args default-output-type]
  (case shared-op
    :get (shared-get-output-type args)
    :merge (shared-merge-output-type args)
    :assoc (shared-assoc-output-type args)
    :dissoc (shared-dissoc-output-type args)
    :update (shared-update-output-type args default-output-type)
    :contains aan/bool-type
    :seq (shared-seq-output-type args)
    default-output-type))
