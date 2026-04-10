(ns skeptic.analysis.annotate.fn
  (:require [skeptic.analysis.calls :as ac]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]))

(defn arg-type-specs
  [dict ns-sym name params]
  (let [entry (when-some [sym name]
                (or (get dict sym)
                    (get dict (ac/qualify-symbol ns-sym sym))))
        arg-specs (get-in (an/normalize-entry entry) [:arglists (count params) :types])]
    (or arg-specs
        (mapv (fn [param]
                {:type at/Dyn
                 :optional? false
                 :name (:form param)})
              params))))

(defn fn-method-param-specs-with-overrides
  [dict ns name params param-type-overrides]
  (let [raw-specs (arg-type-specs dict ns name params)]
    (mapv (fn [param spec]
            (if-let [t (get param-type-overrides (:form param))]
              (assoc spec :type (ato/normalize-type t))
              spec))
          params
          raw-specs)))

(defn fn-method-merge-param-nodes
  [params param-specs]
  (mapv (fn [param spec]
          (let [extra (when (at/fun-type? (:type spec))
                        (ac/fun-type->call-opts (:type spec)))]
            (merge param spec extra)))
        params
        param-specs))

(defn annotate-fn-method
  [{:keys [locals dict name ns recur-targets] :as ctx} node & [param-type-overrides]]
  (let [param-type-overrides (or param-type-overrides {})
        params (:params node)
        param-specs (fn-method-param-specs-with-overrides dict ns name params param-type-overrides)
        annotated-params (fn-method-merge-param-nodes params param-specs)
        param-locals (into locals
                           (map (fn [param]
                                  [(:form param) (assoc (ac/node-info param) :binding-init nil)]))
                           annotated-params)
        recur-targets (cond-> (or recur-targets {})
                        (:loop-id node)
                        (assoc (:loop-id node) (mapv :type annotated-params)))
        body ((:recurse ctx) (assoc ctx
                                    :locals param-locals
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

(defn method->arglist-entry
  [method]
  {:arglist (:arglist method)
   :count (count (:param-specs method))
   :types (mapv (fn [{:keys [type name]}]
                  {:type type
                   :optional? false
                   :name name})
                 (:param-specs method))})

(defn annotate-fn
  [ctx node & [opts]]
  (let [overrides (:param-type-overrides opts {})
        methods (mapv #(annotate-fn-method ctx % overrides) (:methods node))
        arglists (into {}
                       (map (fn [method]
                              [(count (:param-specs method))
                               (method->arglist-entry method)]))
                       methods)
        output-type (av/type-join* (map :output-type methods))
        fn-type (at/->FunT
                 (mapv (fn [method]
                         (at/->FnMethodT (mapv :type (:param-specs method))
                                         (:output-type method)
                                         (count (:param-specs method))
                                         false))
                       methods))]
    (assoc node
           :methods methods
           :output-type output-type
           :arglists arglists
           :type fn-type)))
