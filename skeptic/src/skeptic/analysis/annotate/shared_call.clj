(ns skeptic.analysis.annotate.shared-call
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.map-path :as map-path]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.map-ops.algebra :as amoa]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]))

(defn- shared-get-output-type
  [ctx args]
  (let [[target key-node default-node] args
        key-type (ac/get-key-query ctx key-node)]
    (if default-node
      (amo/map-get-type (:type target) key-type (:type default-node))
      (amo/map-get-type (:type target) key-type))))

(defn- shared-update-output-type
  [args default-output-type]
  (let [[map-node key-node fn-node] args
        literal (when (ac/literal-map-key? key-node)
                  (ac/literal-node-value key-node))]
    (if (keyword? literal)
      (amoa/update-type (:type map-node) literal (:type fn-node))
      default-output-type)))

(defn- shared-seq-output-type
  [args]
  (let [type (:type (first args))]
    (or (cond
          (at/seq-type? type) type
          (at/vector-type? type) (coll/vector-to-homogeneous-seq-type type)
          :else nil)
        (at/Dyn (ato/derive-prov type)))))

(s/defn shared-call-output-type :- ats/SemanticType
  [ctx :- s/Any
   shared-op :- s/Any
   args :- s/Any
   default-output-type :- ats/SemanticType]
  (let [anchor-prov (ato/derive-prov default-output-type)]
    (case shared-op
      :get (shared-get-output-type ctx args)
      :merge (amoa/merge-types anchor-prov (map :type args))
      :assoc (map-path/reduce-assoc-pairs (:type (first args)) (partition 2 (rest args)))
      :dissoc (map-path/reduce-dissoc-keys (:type (first args)) (rest args))
      :update (shared-update-output-type args default-output-type)
      :contains (numeric/bool-type anchor-prov)
      :seq (shared-seq-output-type args)
      default-output-type)))
