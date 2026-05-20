(ns skeptic.analysis.annotate.data
  (:require [schema.core :as s]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.coll :as coll]
            [skeptic.analysis.annotate.prune :as prune]
            [skeptic.analysis.annotate.runner :as runner]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance :as prov]))

(s/defn ^:private finish-def :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode
   meta-node :- (s/maybe aas/AnnotatedNode)
   init-node :- (s/maybe aas/AnnotatedNode)]
  (let [prov (prov/with-ctx ctx)
        inner (or (:type init-node) (at/Dyn prov))]
    (runner/done
     (cond-> (assoc node :type (at/->VarT prov inner))
       meta-node (assoc :meta (prune/project-node meta-node))
       init-node (assoc :init (prune/project-node init-node))))))

(s/defn annotate-def :- runner/Step
  [{:keys [locals] :as ctx} :- s/Any node :- aas/AnnotatedNode]
  (let [recurse-step (:recurse-step ctx)
        init-ctx (assoc ctx :locals locals :name (:name node))
        run-init (fn [meta-node]
                   (if-some [init (:init node)]
                     (runner/call recurse-step init-ctx init
                                  (fn [init-node]
                                    (finish-def ctx node meta-node init-node)))
                     (finish-def ctx node meta-node nil)))]
    (if-some [meta-node (:meta node)]
      (runner/call recurse-step ctx meta-node run-init)
      (run-init nil))))

(s/defn annotate-vector :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/sequence-children
   ctx (:items node)
   (fn [items]
     (let [prov (prov/with-ctx ctx)
           item-types (mapv #(ato/normalize-type prov (or (:type %) (at/Dyn prov))) items)]
       (runner/done
        (assoc node
               :items items
               :type (at/->SeqT (prov/with-refs prov (mapv prov/of item-types))
                                (at/pattern-from-prefix-tail item-types nil)
                                :vector)))))))

(s/defn annotate-set :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/sequence-children
   ctx (:items node)
   (fn [items]
     (let [prov (prov/with-ctx ctx)
           joined (if (seq items)
                    (av/type-join* prov (map :type items))
                    (at/Dyn prov))
           t (ato/normalize-type prov #{joined})]
       (runner/done
        (assoc node :items items :type (assoc t :prov (prov/with-refs (:prov t) [(prov/of joined)]))))))))

(s/defn annotate-map :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/sequence-children
   ctx (:keys node)
   (fn [keys]
     (runner/sequence-children
      ctx (:vals node)
      (fn [vals]
        (let [prov (prov/with-ctx ctx)
              entries (into {}
                            (map (fn [[key-node val-node]]
                                   [(ac/map-literal-key-type ctx key-node) (:type val-node)]))
                            (map vector keys vals))
              refs (into [] (mapcat (fn [[k v]] [(prov/of k) (prov/of v)])) entries)
              t (ato/normalize-type prov entries)]
          (runner/done
           (assoc node :keys keys :vals vals :type (assoc t :prov (prov/with-refs (:prov t) refs))))))))))

(s/defn annotate-new :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/call (:recurse-step ctx) ctx (:class node)
   (fn [class-node]
     (runner/sequence-children
      ctx (:args node)
      (fn [args]
        (let [prov (prov/with-ctx ctx)
              type (or (coll/lazy-seq-new-type class-node args)
                       (some->> (:val class-node) (av/class->type prov))
                       (aapi/dyn ctx))]
          (runner/done
           (assoc node :class class-node :args args :type type))))))))

(s/defn annotate-with-meta :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [recurse-step (:recurse-step ctx)]
    (runner/call recurse-step ctx (:meta node)
     (fn [meta-node]
       (runner/call recurse-step ctx (:expr node)
        (fn [expr-node]
          (runner/done
           (merge node
                  {:meta (prune/project-node meta-node) :expr expr-node}
                  (ac/node-info expr-node)))))))))

(s/defn annotate-throw :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/call (:recurse-step ctx) ctx (:exception node)
   (fn [exception]
     (let [prov (prov/with-ctx ctx)]
       (runner/done
        (assoc node :exception exception :type (at/BottomType prov)))))))

(s/defn annotate-catch :- runner/Step
  [{:keys [locals] :as ctx} :- s/Any node :- aas/AnnotatedNode]
  (let [recurse-step (:recurse-step ctx)
        prov (prov/with-ctx ctx)]
    (runner/call recurse-step ctx (:class node)
     (fn [class-node]
       (let [caught-type (or (some->> (:val class-node) (av/class->type prov))
                             (at/Dyn prov))
             local-node (assoc (:local node) :type caught-type)
             body-ctx (assoc ctx :locals (assoc locals (:form (:local node)) {:type caught-type}))]
         (runner/call recurse-step body-ctx (:body node)
          (fn [body]
            (runner/done
             (assoc node
                    :class class-node
                    :local local-node
                    :body body
                    :type (:type body))))))))))

(s/defn ^:private finish-try :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode
   body :- aas/AnnotatedNode
   catches :- [aas/AnnotatedNode]
   finally-node :- (s/maybe aas/AnnotatedNode)]
  (let [prov (prov/with-ctx ctx)
        type (av/type-join* prov (cons (:type body) (map :type catches)))
        origin (when (empty? catches) (aapi/node-origin body))]
    (runner/done
     (cond-> (assoc node :body body :catches catches :type type)
       finally-node (assoc :finally finally-node)
       origin       (assoc :origin origin)))))

(s/defn annotate-try :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [recurse-step (:recurse-step ctx)]
    (runner/call recurse-step ctx (:body node)
     (fn [body]
       (letfn [(walk-catches [acc remaining]
                 (if (empty? remaining)
                   (if-some [fin (:finally node)]
                     (runner/call recurse-step ctx fin
                                  (fn [finally-node]
                                    (finish-try ctx node body acc finally-node)))
                     (finish-try ctx node body acc nil))
                   (runner/call annotate-catch ctx (first remaining)
                                (fn [c] (walk-catches (conj acc c) (rest remaining))))))]
         (walk-catches [] (:catches node)))))))

(s/defn annotate-quote :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/call (:recurse-step ctx) ctx (:expr node)
   (fn [expr]
     (let [prov (prov/with-ctx ctx)]
       (runner/done
        (assoc node :expr expr :type (av/type-of-value prov (-> node :form second))))))))
