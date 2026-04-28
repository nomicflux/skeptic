(ns skeptic.analysis.annotate.data
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(s/defn annotate-def :- s/Any
  [{:keys [locals] :as ctx} :- s/Any, node :- s/Any]
  (let [meta-node (when-some [meta-node (:meta node)]
                    ((:recurse ctx) ctx meta-node))
        init-node (when-some [init-node (:init node)]
                    ((:recurse ctx)
                     (assoc ctx :locals locals :name (:name node))
                     init-node))
        inner (or (:type init-node) (aapi/dyn ctx))]
    (cond-> (assoc node :type (at/->VarT (prov/with-ctx ctx) inner))
      meta-node (assoc :meta meta-node)
      init-node (assoc :init init-node))))

(s/defn annotate-vector :- s/Any
  [ctx :- s/Any, node :- s/Any]
  (let [items (mapv #((:recurse ctx) ctx %) (:items node))
        item-types (mapv #(aapi/normalize-type ctx (or (:type %) (aapi/dyn ctx))) items)]
    (assoc node
           :items items
           :type (at/->VectorT (prov/with-refs (prov/with-ctx ctx) (mapv prov/of item-types))
                               item-types
                               (coll/vec-homogeneous-items? item-types)))))

(s/defn annotate-set :- s/Any
  [ctx :- s/Any, node :- s/Any]
  (let [items (mapv #((:recurse ctx) ctx %) (:items node))
        joined (if (seq items)
                 (av/type-join* (prov/with-ctx ctx) (map :type items))
                 (aapi/dyn ctx))
        t (aapi/normalize-type ctx #{joined})]
    (assoc node :items items :type (assoc t :prov (prov/with-refs (:prov t) [(prov/of joined)])))))

(s/defn annotate-map :- s/Any
  [ctx :- s/Any, node :- s/Any]
  (let [keys (mapv #((:recurse ctx) ctx %) (:keys node))
        vals (mapv #((:recurse ctx) ctx %) (:vals node))
        entries (into {}
                      (map (fn [[key-node val-node]]
                             [(ac/map-literal-key-type ctx key-node) (:type val-node)]))
                      (map vector keys vals))
        refs (into [] (mapcat (fn [[k v]] [(prov/of k) (prov/of v)])) entries)
        t (aapi/normalize-type ctx entries)]
    (assoc node :keys keys :vals vals :type (assoc t :prov (prov/with-refs (:prov t) refs)))))

(s/defn annotate-new :- s/Any
  [ctx :- s/Any, node :- s/Any]
  (let [class-node ((:recurse ctx) ctx (:class node))
        args (mapv #((:recurse ctx) ctx %) (:args node))
        prov (prov/with-ctx ctx)
        type (or (coll/lazy-seq-new-type class-node args)
                 (some->> (:val class-node) (av/class->type prov))
                 (aapi/dyn ctx))]
    (assoc node :class class-node :args args :type type)))

(s/defn annotate-with-meta :- s/Any
  [ctx :- s/Any, node :- s/Any]
  (let [meta-node ((:recurse ctx) ctx (:meta node))
        expr-node ((:recurse ctx) ctx (:expr node))]
    (merge node {:meta meta-node :expr expr-node} (ac/node-info expr-node))))

(s/defn annotate-throw :- s/Any
  [ctx :- s/Any, node :- s/Any]
  (let [exception ((:recurse ctx) ctx (:exception node))]
    (assoc node :exception exception :type (aapi/bottom ctx))))

(s/defn annotate-catch :- s/Any
  [{:keys [locals] :as ctx} :- s/Any, node :- s/Any]
  (let [class-node ((:recurse ctx) ctx (:class node))
        caught-type (or (some->> (:val class-node) (av/class->type (prov/with-ctx ctx)))
                        (aapi/dyn ctx))
        local-node (assoc (:local node) :type caught-type)
        body ((:recurse ctx)
              (assoc ctx :locals (assoc locals (:form (:local node)) {:type caught-type}))
              (:body node))]
    (assoc node
           :class class-node
           :local local-node
           :body body
           :type (:type body))))

(s/defn annotate-try :- s/Any
  [ctx :- s/Any, node :- s/Any]
  (let [body ((:recurse ctx) ctx (:body node))
        catches (mapv #(annotate-catch ctx %) (:catches node))
        finally-node (when-some [finally-node (:finally node)]
                       ((:recurse ctx) ctx finally-node))
        type (av/type-join* (prov/with-ctx ctx) (cons (:type body) (map :type catches)))
        origin (when (empty? catches) (aapi/node-origin body))]
    (cond-> (assoc node :body body :catches catches :type type)
      finally-node (assoc :finally finally-node)
      origin       (assoc :origin origin))))

(s/defn annotate-quote :- s/Any
  [ctx :- s/Any, node :- s/Any]
  (let [expr ((:recurse ctx) ctx (:expr node))]
    (assoc node :expr expr :type (av/type-of-value (prov/with-ctx ctx) (-> node :form second)))))
