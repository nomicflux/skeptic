(ns skeptic.analysis.annotate.invoke
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.fn :as fn-annotate]
            [skeptic.analysis.annotate.map-projection :as map-projection]
            [skeptic.analysis.annotate.invoke-output :as invoke-output]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]))

(s/defn resolve-unary-fn-arg-type-hint :- (s/maybe s/Any)
  [ctx fn-ast args]
  (let [local-fn (when (= :local (:op fn-ast))
                   (some-> (get (:locals ctx) (:form fn-ast))
                           :fn-binding-node))
        source-fn (cond
                    (and (= :fn (:op fn-ast))
                         (= 1 (count (:methods fn-ast)))
                         (= 1 (count args)))
                    fn-ast

                    (and (= :fn (:op local-fn))
                         (= 1 (count (:methods local-fn)))
                         (= 1 (count args)))
                    local-fn
                    :else nil)]
    (when source-fn
      (let [param-form (:form (first (:params (first (:methods source-fn)))))]
        {:fn-node (fn-annotate/annotate-fn
                   ctx
                   source-fn
                   {:param-type-overrides {param-form (or (:type (first args)) (aapi/dyn ctx))}})}))))

(defn- annotate-fn-and-args
  [ctx node]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))]
    (if-let [hint (resolve-unary-fn-arg-type-hint ctx (:fn node) args)]
      [(:fn-node hint) args]
      [((:recurse ctx) ctx (:fn node))
       args])))

(defn- build-invoke-node
  [ctx node fn-node args output-type fn-type expected-argtypes]
  (assoc node
         :fn fn-node
         :args args
         :actual-argtypes (mapv :type args)
         :expected-argtypes (mapv #(aapi/normalize-type ctx %) expected-argtypes)
         :type (aapi/normalize-type ctx output-type)
         :fn-type (aapi/normalize-type ctx fn-type)))

(s/defn annotate-invoke :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [[fn-node args] (annotate-fn-and-args ctx node)
        call-info (ac/call-info ctx fn-node args)
        output-type (invoke-output/invoke-output-type ctx fn-node args (:output-type call-info))
        output-type (or (numeric/invoke-integral-math-narrow-type
                         (ato/derive-prov output-type) fn-node args (mapv :type args))
                        output-type)]
    (build-invoke-node ctx node fn-node args output-type (:fn-type call-info) (:expected-argtypes call-info))))

(s/defn annotate-keyword-invoke :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [target ((:recurse ctx) ctx (:target node))
        keyword-node ((:recurse ctx) ctx (:keyword node))
        query (ac/get-key-query ctx keyword-node)
        type (amo/map-get-type (:type target) query)]
    (cond-> (assoc node
                   :target target
                   :keyword keyword-node
                   :type type
                   :actual-argtypes [(:type target)]
                   :expected-argtypes [(aapi/dyn ctx)])
      true
      (assoc :origin (map-projection/map-key-lookup-origin ctx target query amo/no-default)))))
