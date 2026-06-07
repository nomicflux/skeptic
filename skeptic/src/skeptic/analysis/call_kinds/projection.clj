(ns skeptic.analysis.call-kinds.projection
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.map-projection :as map-projection]
            [skeptic.analysis.call-kinds.symbols :as symbols]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]))

(s/defn literal-key-projection :- (s/maybe [(s/one s/Keyword "kw") (s/one s/Any "target")])
  [node :- s/Any]
  (let [op (aapi/node-op node)
        [target key-node] (cond
                            (and (= :invoke op)
                                 (symbols/get? (aapi/call-fn-node node)))
                            (aapi/call-args node)

                            (and (= :static-call op)
                                 (symbols/static-get? node))
                            (aapi/call-args node))]
    (when (and target
               (aapi/stable-identity-node? target)
               (ac/literal-map-key? key-node))
      (let [key (ac/literal-node-value key-node)]
        (when (keyword? key) [key target])))))

(s/defn static-get-map-key-lookup-origin :- (s/maybe s/Any)
  [ctx :- s/Any
   node :- s/Any
   args :- [s/Any]]
  (when (and (symbols/static-get? node)
             (<= 2 (count args) 3))
    (map-projection/map-key-lookup-origin
     ctx (first args)
     (ac/get-key-query ctx (second args))
     (if (= 3 (count args)) (:type (nth args 2)) amo/no-default))))
