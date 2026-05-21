(ns skeptic.analysis.annotate.specialize
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.fn :as fn-annotate]
            [skeptic.analysis.annotate.runner :as runner]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(defprotocol IdentityWrapped
  (identity-value [this]))

(deftype IdentityKey [value]
  IdentityWrapped
  (identity-value [_] value)
  Object
  (equals [_ other]
    (and (satisfies? IdentityWrapped other)
         (identical? value (identity-value other))))
  (hashCode [_]
    (System/identityHashCode value)))

(defn- identity-key
  [value]
  (when value
    (IdentityKey. value)))

(defn- specialization-state
  [ctx]
  (or (:fn-specialization-state ctx)
      (throw (IllegalStateException.
              "Local fn specialization requires per-annotate specialization state"))))

(defn initial-state
  []
  {:active  (atom {})
   :cache   (atom {})
   :results (atom {})})

(defn- project-context-key
  [ctx]
  {:ns (:ns ctx)
   :name (:name ctx)
   :dict (identity-key (:dict ctx))
   :accessor-summaries (identity-key (:accessor-summaries ctx))})

(defn- ordered-normalized-overrides
  [ctx source-fn overrides]
  (let [params (:params (first (:methods source-fn)))]
    (mapv (fn [param]
            (let [param-form (:form param)]
              [param-form (aapi/normalize-type ctx (get overrides param-form))]))
          params)))

(defn- specialization-key
  [ctx source-fn overrides]
  {:source-fn (identity-key source-fn)
   :overrides (ordered-normalized-overrides ctx source-fn overrides)
   :context (project-context-key ctx)})

(defn- specialization-ref-id
  [source-fn]
  {:kind :local-fn-specialization
   :name (or (:name source-fn) (:form source-fn) 'anonymous)
   :id (gensym "specialization__")})

(defn- placeholder-method
  [ctx source-fn overrides output-type]
  (let [method (first (:methods source-fn))
        params (:params method)
        param-specs (fn-annotate/fn-method-param-specs-with-overrides
                     ctx (:dict ctx) (:ns ctx) (:name ctx) params overrides)
        prov (prov/with-ctx ctx)]
    (at/->FnMethodT prov
                    (mapv :type param-specs)
                    output-type
                    (count param-specs)
                    (boolean (:variadic? method))
                    (mapv :name param-specs))))

(defn- placeholder-fn-node
  [ctx source-fn overrides ref]
  (let [fn-type (at/->FunT (prov/with-ctx ctx)
                           [(placeholder-method ctx source-fn overrides ref)])]
    (assoc source-fn
           :type fn-type
           :output-type ref
           :arglists (into {}
                           (map (fn [method]
                                  [(count (:inputs method))
                                   {:arglist (:names method)
                                    :count (count (:inputs method))
                                    :types (mapv (fn [type name]
                                                   {:type type :optional? false :name name})
                                                 (:inputs method)
                                                 (:names method))}]))
                           (:methods fn-type)))))

(s/defn specialize-local-fn :- runner/Step
  [ctx :- s/Any
   source-fn :- aas/AnnotatedNode]
  (let [{:keys [active cache results]} (specialization-state ctx)
        overrides (:fn-specialization-overrides ctx)
        key (specialization-key ctx source-fn overrides)]
    (cond
      (contains? @cache key)
      (runner/done (get @cache key))

      (contains? @active key)
      (let [{:keys [ref]} (get @active key)]
        (runner/done (placeholder-fn-node ctx source-fn overrides ref)))

      :else
      (let [ref-id (specialization-ref-id source-fn)
            ref (at/->SpecializationRefT (prov/with-ctx ctx) ref-id results)]
        (swap! active assoc key {:ref ref})
        (runner/call fn-annotate/annotate-fn
                     (assoc ctx :param-type-overrides overrides)
                     source-fn
                     (fn [fn-node]
                       (swap! results assoc ref-id (:output-type fn-node))
                       (swap! cache assoc key fn-node)
                       (swap! active dissoc key)
                       (runner/done fn-node)))))))
