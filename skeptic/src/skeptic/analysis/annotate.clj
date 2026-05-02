(ns skeptic.analysis.annotate
  (:require [schema.core :as s]
            [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [skeptic.analysis.annotate.base :as base]
            [skeptic.analysis.annotate.control :as control]
            [skeptic.analysis.annotate.data :as data]
            [skeptic.analysis.annotate.fn :as fn-annotate]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.invoke :as invoke]
            [skeptic.analysis.annotate.jvm :as jvm]
            [skeptic.analysis.annotate.match :as match]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(s/defn ^:private annotate-generic :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (assoc (base/annotate-children ctx node) :type (at/Dyn (prov/with-ctx ctx))))

(s/defn ^:private annotate-dispatch :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (case (:op node)
    :binding (base/annotate-binding ctx node)
    :const (base/annotate-const ctx node)
    :def (data/annotate-def ctx node)
    :do (control/annotate-do ctx node)
    :fn (fn-annotate/annotate-fn ctx node)
    :fn-method (fn-annotate/annotate-fn-method ctx node)
    :if (control/annotate-if ctx node)
    :case (match/annotate-case ctx node)
    :instance-call (jvm/annotate-instance-call ctx node)
    :invoke (invoke/annotate-invoke ctx node)
    :keyword-invoke (invoke/annotate-keyword-invoke ctx node)
    :let (control/annotate-let ctx node)
    :loop (control/annotate-loop ctx node)
    :local (base/annotate-local ctx node)
    :map (data/annotate-map ctx node)
    :new (data/annotate-new ctx node)
    :quote (data/annotate-quote ctx node)
    :recur (control/annotate-recur ctx node)
    :set (data/annotate-set ctx node)
    :static-call (jvm/annotate-static-call ctx node)
    :the-var (base/annotate-var-like ctx node)
    :throw (data/annotate-throw ctx node)
    :try (data/annotate-try ctx node)
    :var (base/annotate-var-like ctx node)
    :vector (data/annotate-vector ctx node)
    :with-meta (data/annotate-with-meta ctx node)
    (annotate-generic ctx node)))

(defn- eval-skeptic-type
  [ns-sym type-form]
  (let [target-ns (or (some-> ns-sym find-ns) *ns*)]
    (binding [*ns* target-ns]
      (eval type-form))))

(defn- resolve-skeptic-type
  [ctx node]
  (when-let [type-form (:skeptic/type (meta (aapi/node-form node)))]
    (let [schema (eval-skeptic-type (:ns ctx) type-form)]
      (when-not (ab/schema-domain? schema)
        (throw (IllegalArgumentException.
                (format "Invalid :skeptic/type override: %s" (pr-str type-form)))))
      (ab/schema->type (prov/make-provenance :type-override
                                             (:name ctx) (:ns ctx) nil)
                       schema))))

(defn- apply-type-override
  [annotated ctx node]
  (if-let [override (resolve-skeptic-type ctx node)]
    (aapi/with-type annotated override)
    annotated))

(s/defn annotate-node :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (let [ctx (assoc ctx :recurse annotate-node)]
    (-> (annotate-dispatch ctx node)
        (apply-type-override ctx node)
        abr/strip-derived-types)))

(s/defn annotate-ast :- aas/AnnotatedNode
  ([dict :- s/Any ast :- aas/AnnotatedNode]
   (annotate-ast dict ast {}))
  ([dict :- s/Any ast :- aas/AnnotatedNode {:keys [locals name ns assumptions accessor-summaries]} :- s/Any]
   (annotate-node (prov/set-ctx {:dict (or dict {})
                                 :locals (or locals {})
                                 :assumptions (vec assumptions)
                                 :recur-targets {}
                                 :name name
                                 :ns ns
                                 :accessor-summaries (or accessor-summaries {})}
                                (prov/inferred {:name name :ns ns}))
                  ast)))

(defn- target-ns
  [ns-sym]
  (or (some-> ns-sym find-ns)
      (some-> ns-sym create-ns)
      *ns*))

(defn- analyze-env
  [target-ns locals source-file]
  (cond-> (assoc (ta/empty-env)
                 :ns (ns-name target-ns)
                 :locals (into {}
                               (map-indexed (fn [idx sym]
                                              [sym (aapi/synthetic-binding-node idx sym)]))
                               (keys locals)))
    source-file
    (assoc :file source-file)))

(s/defn ^:private normalize-raw-ast :- aas/AnnotatedNode
  [ast :- aas/RawAnalyzerAst]
  (ana.ast/prewalk ast (fn [node]
                         (cond-> node
                           (= :const (:op node)) (dissoc :type)))))

(s/defn analyze-form :- aas/AnnotatedNode
  ([form :- s/Any]
   (analyze-form form {}))
  ([form :- s/Any {:keys [locals ns source-file]} :- s/Any]
   (let [target-ns (target-ns ns)
         env (binding [*ns* target-ns]
               (analyze-env target-ns locals source-file))]
     (binding [*ns* target-ns]
       (normalize-raw-ast (ana.jvm/analyze form env))))))

(s/defn annotate-form-loop :- aas/AnnotatedNode
  ([dict :- s/Any form :- s/Any]
   (annotate-form-loop dict form {}))
  ([dict :- s/Any form :- s/Any opts :- s/Any]
   (annotate-ast dict
                 (analyze-form form opts)
                 opts)))
