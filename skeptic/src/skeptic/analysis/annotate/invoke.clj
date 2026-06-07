(ns skeptic.analysis.annotate.invoke
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.map-projection :as map-projection]
            [skeptic.analysis.annotate.runner :as runner]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.annotate.specialize :as specialize]
            [skeptic.analysis.call-kinds.invoke-output :as ck-invoke-output]
            [skeptic.analysis.call-kinds.numeric :as ck-numeric]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]))

(s/defn resolve-unary-fn-arg-type-hint :- (s/maybe s/Any)
  [ctx fn-ast args]
  (let [local-fn (when (aapi/local-node? fn-ast)
                   (some-> (get (:locals ctx) (:form fn-ast))
                           :fn-binding-node))
        source-fn (cond
                    (and (aapi/fn-node? fn-ast)
                         (= 1 (count (:methods fn-ast)))
                         (= 1 (count args)))
                    fn-ast

                    (and local-fn
                         (aapi/fn-node? local-fn)
                         (= 1 (count (:methods local-fn)))
                         (= 1 (count args)))
                    local-fn
                    :else nil)]
    (when source-fn
      (let [param-form (:form (first (:params (first (:methods source-fn)))))]
        {:source-fn source-fn
         :overrides {param-form (or (:type (first args)) (aapi/dyn ctx))}}))))

(defn- build-invoke-node
  [ctx node fn-node args output-type fn-type expected-argtypes]
  (assoc node
         :fn fn-node
         :args args
         :actual-argtypes (mapv :type args)
         :expected-argtypes (mapv #(aapi/normalize-type ctx %) expected-argtypes)
         :type (aapi/normalize-type ctx output-type)
         :fn-type (aapi/normalize-type ctx fn-type)))

(defn- finish-invoke
  [ctx node args fn-node]
  (let [call-info (ac/call-info ctx fn-node args)
        output-type (ck-invoke-output/invoke-output-type ctx fn-node args (:output-type call-info))
        output-type (or (ck-numeric/invoke-numeric-narrow-type
                         (ato/derive-prov output-type) fn-node args (mapv :type args))
                        output-type)]
    (runner/done
     (build-invoke-node ctx node fn-node args output-type (:fn-type call-info) (:expected-argtypes call-info)))))

(s/defn annotate-invoke :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/sequence-children ctx (:args node)
   (fn [args]
     (if-let [hint (resolve-unary-fn-arg-type-hint ctx (:fn node) args)]
       (runner/call specialize/specialize-local-fn
                    (assoc ctx :fn-specialization-overrides (:overrides hint))
                    (:source-fn hint)
                    (fn [fn-node] (finish-invoke ctx node args fn-node)))
       (runner/call (:recurse-step ctx) ctx (:fn node)
                    (fn [fn-node] (finish-invoke ctx node args fn-node)))))))

(s/defn annotate-keyword-invoke :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/call (:recurse-step ctx) ctx (:target node)
   (fn [target]
     (runner/call (:recurse-step ctx) ctx (:keyword node)
      (fn [keyword-node]
        (let [query (ac/get-key-query ctx keyword-node)
              target-type (or (aapi/node-type target) (aapi/dyn ctx))
              type (or (amo/map-get-type target-type query) (aapi/dyn ctx))]
          (runner/done
           (assoc node
                  :target target
                  :keyword keyword-node
                  :type type
                  :actual-argtypes [target-type]
                  :expected-argtypes [(aapi/dyn ctx)]
                  :origin (map-projection/map-key-lookup-origin ctx target query amo/no-default)))))))))
