(ns skeptic.analysis.annotate.invoke
  (:require [skeptic.analysis.annotate.fn :as fn-annotate]
            [skeptic.analysis.annotate.map-projection :as map-projection]
            [skeptic.analysis.annotate.invoke-output :as invoke-output]
            [skeptic.analysis.annotate.numeric :as numeric]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.map-ops :as amo]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]))

(defn resolve-unary-fn-arg-type-hint
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
                   {:param-type-overrides {param-form (or (:type (first args)) at/Dyn)}})}))))

(defn- annotate-fn-and-args
  [ctx node]
  (let [args (mapv #((:recurse ctx) ctx %) (:args node))]
    (if-let [hint (resolve-unary-fn-arg-type-hint ctx (:fn node) args)]
      [(:fn-node hint) args]
      [((:recurse ctx) ctx (:fn node))
       args])))

(defn- build-invoke-node
  [node fn-node args output-type fn-type expected-argtypes]
  (assoc node
         :fn fn-node
         :args args
         :actual-argtypes (mapv :type args)
         :expected-argtypes (mapv ato/normalize-type expected-argtypes)
         :type (ato/normalize-type output-type)
         :fn-type (ato/normalize-type fn-type)))

(defn annotate-invoke
  [ctx node]
  (let [[fn-node args] (annotate-fn-and-args ctx node)
        call-info (ac/call-info fn-node args)
        output-type (invoke-output/invoke-output-type fn-node args (:output-type call-info))
        output-type (or (numeric/invoke-integral-math-narrow-type
                         fn-node args (mapv :type args))
                        output-type)]
    (build-invoke-node node fn-node args output-type (:fn-type call-info) (:expected-argtypes call-info))))

(defn annotate-keyword-invoke
  [ctx node]
  (let [target ((:recurse ctx) ctx (:target node))
        keyword-node ((:recurse ctx) ctx (:keyword node))
        query (ac/get-key-query keyword-node)
        type (amo/map-get-type (:type target) query)]
    (cond-> (assoc node
                   :target target
                   :keyword keyword-node
                   :type type
                   :actual-argtypes [(:type target)]
                   :expected-argtypes [at/Dyn])
      true
      (assoc :origin (map-projection/map-key-lookup-origin target query)))))
