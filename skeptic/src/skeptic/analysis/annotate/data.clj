(ns skeptic.analysis.annotate.data
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(s/defn annotate-def :- aas/AnnotatedNode
  [{:keys [locals] :as ctx} :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)
        meta-node (when-some [meta-node (:meta node)]
                    ((:recurse ctx) ctx meta-node))
        init-node (when-some [init-node (:init node)]
                    ((:recurse ctx)
                     (assoc ctx :locals locals :name (:name node))
                     init-node))
        inner (or (:type init-node) (at/Dyn prov))]
    (cond-> (assoc node :type (at/->VarT prov inner))
      meta-node (assoc :meta meta-node)
      init-node (assoc :init init-node))))

(s/defn annotate-vector :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)
        items (mapv #((:recurse ctx) ctx %) (:items node))
        item-types (mapv #(ato/normalize-type prov (or (:type %) (at/Dyn prov))) items)]
    (assoc node
           :items items
           :type (at/->VectorT (prov/with-refs prov (mapv prov/of item-types))
                               item-types
                               (coll/vec-homogeneous-items? item-types)))))

(s/defn annotate-set :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)
        items (mapv #((:recurse ctx) ctx %) (:items node))
        joined (if (seq items)
                 (av/type-join* prov (map :type items))
                 (at/Dyn prov))
        t (ato/normalize-type prov #{joined})]
    (assoc node :items items :type (assoc t :prov (prov/with-refs (:prov t) [(prov/of joined)])))))

(s/defn annotate-map :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)
        keys (mapv #((:recurse ctx) ctx %) (:keys node))
        vals (mapv #((:recurse ctx) ctx %) (:vals node))
        entries (into {}
                      (map (fn [[key-node val-node]]
                             [(ac/map-literal-key-type ctx key-node) (:type val-node)]))
                      (map vector keys vals))
        refs (into [] (mapcat (fn [[k v]] [(prov/of k) (prov/of v)])) entries)
        t (ato/normalize-type prov entries)]
    (assoc node :keys keys :vals vals :type (assoc t :prov (prov/with-refs (:prov t) refs)))))

(s/defn annotate-new :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [class-node ((:recurse ctx) ctx (:class node))
        args (mapv #((:recurse ctx) ctx %) (:args node))
        prov (prov/with-ctx ctx)
        type (or (coll/lazy-seq-new-type class-node args)
                 (some->> (:val class-node) (av/class->type prov))
                 (aapi/dyn ctx))]
    (assoc node :class class-node :args args :type type)))

(s/defn annotate-with-meta :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [meta-node ((:recurse ctx) ctx (:meta node))
        expr-node ((:recurse ctx) ctx (:expr node))]
    (merge node {:meta meta-node :expr expr-node} (ac/node-info expr-node))))

(s/defn annotate-throw :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)
        exception ((:recurse ctx) ctx (:exception node))]
    (assoc node :exception exception :type (at/BottomType prov))))

(s/defn annotate-catch :- aas/AnnotatedNode
  [{:keys [locals] :as ctx} :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)
        class-node ((:recurse ctx) ctx (:class node))
        caught-type (or (some->> (:val class-node) (av/class->type prov))
                        (at/Dyn prov))
        local-node (assoc (:local node) :type caught-type)
        body ((:recurse ctx)
              (assoc ctx :locals (assoc locals (:form (:local node)) {:type caught-type}))
              (:body node))]
    (assoc node
           :class class-node
           :local local-node
           :body body
           :type (:type body))))

(s/defn annotate-try :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)
        body ((:recurse ctx) ctx (:body node))
        catches (mapv #(annotate-catch ctx %) (:catches node))
        finally-node (when-some [finally-node (:finally node)]
                       ((:recurse ctx) ctx finally-node))
        type (av/type-join* prov (cons (:type body) (map :type catches)))
        origin (when (empty? catches) (aapi/node-origin body))]
    (cond-> (assoc node :body body :catches catches :type type)
      finally-node (assoc :finally finally-node)
      origin       (assoc :origin origin))))

(s/defn annotate-quote :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [prov (prov/with-ctx ctx)
        expr ((:recurse ctx) ctx (:expr node))]
    (assoc node :expr expr :type (av/type-of-value prov (-> node :form second)))))
