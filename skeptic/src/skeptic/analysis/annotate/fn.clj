(ns skeptic.analysis.annotate.fn
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(defn- dict-fun-type
  [dict ns-sym name]
  (some-> (or (get dict name) (get dict (ac/qualify-symbol ns-sym name)))
          (#(when (at/fun-type? %) %))))

(defn- method-at-arity
  [ft arity]
  (or (some #(when (= (:min-arity %) arity) %) (at/fun-methods ft))
      (some #(when (:variadic? %) %) (at/fun-methods ft))))

(s/defn arg-type-specs :- [s/Any]
  [ctx dict ns-sym name params]
  (let [arity (count params)
        method (some-> (dict-fun-type dict ns-sym name) (method-at-arity arity))
        inputs (some-> method at/fn-method-inputs)
        names (some-> method at/fn-method-input-names)]
    (if (seq inputs)
      (mapv (fn [i param]
              {:type (get inputs i (aapi/dyn ctx))
               :optional? false
               :name (or (get names i) (:form param))})
            (range arity)
            params)
      (mapv (fn [param]
              {:type (aapi/dyn ctx) :optional? false :name (:form param)})
            params))))

(s/defn fn-method-param-specs-with-overrides :- [s/Any]
  [ctx dict ns-sym name params param-type-overrides]
  (mapv (fn [param spec]
          (if-let [type (get param-type-overrides (:form param))]
            (assoc spec :type (aapi/normalize-type ctx type))
            spec))
        params
        (arg-type-specs ctx dict ns-sym name params)))

(s/defn fn-method-merge-param-nodes :- [s/Any]
  [params param-specs]
  (mapv (fn [param spec]
          (let [extra (when (at/fun-type? (:type spec))
                        (ac/fun-type->call-opts (:type spec)))]
            (merge param spec extra)))
        params
        param-specs))

(defn- param-locals
  [ctx locals annotated-params]
  (into locals
        (map (fn [param] [(:form param) (or (:type param) (aapi/dyn ctx))]))
        annotated-params))

(s/defn annotate-fn-method :- aas/AnnotatedNode
  [{:keys [locals dict name ns recur-targets] :as ctx} :- s/Any node :- aas/AnnotatedNode & [param-type-overrides]]
  (let [param-type-overrides (or param-type-overrides {})
        param-specs (fn-method-param-specs-with-overrides
                     ctx dict ns name (:params node) param-type-overrides)
        annotated-params (fn-method-merge-param-nodes (:params node) param-specs)
        recur-targets (cond-> (or recur-targets {})
                        (:loop-id node)
                        (assoc (:loop-id node) (mapv :type annotated-params)))
        body ((:recurse ctx)
              (assoc ctx
                     :locals (param-locals ctx locals annotated-params)
                     :recur-targets recur-targets
                     :name nil)
              (:body node))]
    (assoc node
           :params annotated-params
           :body body
           :type (:type body)
           :output-type (:type body)
           :arglist (mapv :name param-specs)
           :param-specs param-specs)))

(s/defn method->arglist-entry :- s/Any
  [method]
  {:arglist (:arglist method)
   :count (count (:param-specs method))
   :types (mapv (fn [{:keys [type name]}]
                  {:type type :optional? false :name name})
                (:param-specs method))})

(defn- method-fn-type
  [ctx method]
  (at/->FnMethodT (prov/with-ctx ctx)
                  (mapv :type (:param-specs method))
                  (:output-type method)
                  (count (:param-specs method))
                  (boolean (:variadic? method))
                  (mapv :name (:param-specs method))))

(s/defn annotate-fn :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode & [opts]]
  (let [overrides (:param-type-overrides opts {})
        methods (mapv #(annotate-fn-method ctx % overrides) (:methods node))
        arglists (into {} (map (juxt #(count (:param-specs %)) method->arglist-entry)) methods)
        output-type (av/type-join* (prov/with-ctx ctx) (map :output-type methods))
        fn-type (at/->FunT (prov/with-ctx ctx) (mapv #(method-fn-type ctx %) methods))]
    (assoc node
           :methods methods
           :output-type output-type
           :arglists arglists
           :type fn-type)))
