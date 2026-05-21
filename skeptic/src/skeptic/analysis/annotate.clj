(ns skeptic.analysis.annotate
  (:require [schema.core :as s]
            [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.ast :as ana.ast]
            [clojure.tools.analyzer.env :as ana.env]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [skeptic.analysis.annotate.base :as base]
            [skeptic.analysis.annotate.control :as control]
            [skeptic.analysis.annotate.data :as data]
            [skeptic.analysis.annotate.fn :as fn-annotate]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.cljs :as cljs]
            [skeptic.analysis.annotate.invoke :as invoke]
            [skeptic.analysis.annotate.jvm :as jvm]
            [skeptic.analysis.annotate.match :as match]
            [skeptic.analysis.annotate.runner :as runner]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.annotate.specialize :as specialize]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.types :as at]
            [skeptic.provenance :as prov]))

(s/defn ^:private annotate-generic :- runner/Step
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/call base/annotate-children ctx node
               (fn [annotated]
                 (runner/done (assoc annotated :type (at/Dyn (prov/with-ctx ctx)))))))

(s/defn ^:private annotate-dispatch :- (s/cond-pre runner/Step aas/AnnotatedNode)
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (case (aapi/node-op node)
    :binding (base/annotate-binding ctx node)
    :const (base/annotate-const ctx node)
    :def (data/annotate-def ctx node)
    :do (control/annotate-do ctx node)
    :fn (fn-annotate/annotate-fn ctx node)
    :fn-method (fn-annotate/annotate-fn-method ctx node)
    :if (control/annotate-if ctx node)
    :case (match/annotate-case ctx node)
    :host-call (cljs/annotate-host-call ctx node)
    :host-field (cljs/annotate-host-field ctx node)
    :instance-call (jvm/annotate-instance-call ctx node)
    :invoke (invoke/annotate-invoke ctx node)
    :js (cljs/annotate-js ctx node)
    :js-var (cljs/annotate-js-var ctx node)
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
                                             (:name ctx) (:ns ctx) nil [] :clj)
                       schema))))

(defn- apply-type-override
  [annotated ctx node]
  (if-let [override (resolve-skeptic-type ctx node)]
    (aapi/with-type annotated override)
    annotated))

(s/defn ^:private finalize-annotated :- aas/AnnotatedNode
  [ctx       :- s/Any
   node      :- aas/AnnotatedNode
   annotated :- aas/AnnotatedNode]
  (let [result (-> annotated
                   (apply-type-override ctx node)
                   abr/strip-derived-types)]
    (when-not (:type result)
      (throw (IllegalStateException.
              (format "annotate-node produced node without :type for op %s; form: %s"
                      (pr-str (:op node))
                      (pr-str (:form node))))))
    result))

(defn- annotate-finalizer
  [helper-fn ctx node annotated]
  (if (identical? helper-fn annotate-dispatch)
    (finalize-annotated ctx node annotated)
    annotated))

(s/defn annotate-node :- aas/AnnotatedNode
  [ctx :- s/Any node :- aas/AnnotatedNode]
  (runner/run-with-finalizer annotate-dispatch
                             (assoc ctx
                                    :recurse annotate-node
                                    :recurse-step annotate-dispatch)
                             node
                             annotate-finalizer))

(s/defn annotate-ast :- aas/AnnotatedNode
  [dict :- s/Any ast :- aas/AnnotatedNode {:keys [locals name ns assumptions accessor-summaries lang]} :- s/Any]
  (annotate-node (prov/set-ctx {:dict (or dict {})
                                :locals (or locals {})
                                :assumptions (vec assumptions)
                                :recur-targets {}
                                :fn-specialization-state (specialize/initial-state)
                                :name name
                                :ns ns
                                :accessor-summaries (or accessor-summaries {})}
                               (prov/inferred {:name name :ns ns} lang))
                 ast))

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

(def ^:private skeptic-passes-opts
  (assoc ana.jvm/default-passes-opts
         :validate/wrong-tag-handler (fn [t _ast] {t Object})))

(defn- loaded-namespace-analyzer-env
  []
  (doto (ana.jvm/global-env)
    ;; Pipeline requires JVM namespaces before source checking, so the initial
    ;; namespace snapshot is the canonical resolution state for the source pass.
    (swap! assoc :update-ns-map! (fn [] nil))))

(defn with-loaded-namespace-analyzer-env
  [f]
  (if ana.env/*env*
    (f)
    (ana.env/with-env (loaded-namespace-analyzer-env)
      (f))))

(s/defn analyze-form :- aas/AnnotatedNode
  ([form :- s/Any]
   (analyze-form form {}))
  ([form :- s/Any {:keys [locals ns source-file]} :- s/Any]
   (let [target-ns (target-ns ns)
         env (binding [*ns* target-ns]
               (analyze-env target-ns locals source-file))]
     (binding [*ns* target-ns]
       (normalize-raw-ast (ana.jvm/analyze form env {:passes-opts skeptic-passes-opts}))))))

(s/defn annotate-form-loop :- aas/AnnotatedNode
  [dict :- s/Any form :- s/Any opts :- s/Any]
  (annotate-ast dict
                (analyze-form form opts)
                opts))
