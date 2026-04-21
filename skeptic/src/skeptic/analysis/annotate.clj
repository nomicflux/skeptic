(ns skeptic.analysis.annotate
  (:require [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [skeptic.analysis.annotate.base :as base]
            [skeptic.analysis.annotate.control :as control]
            [skeptic.analysis.annotate.data :as data]
            [skeptic.analysis.annotate.fn :as fn-annotate]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.invoke :as invoke]
            [skeptic.analysis.annotate.jvm :as jvm]
            [skeptic.analysis.annotate.match :as match]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.types :as at]))

(defn node-location
  [node]
  (select-keys (meta (:form node)) [:file :line :column :end-line :end-column]))

(defn node-error-context
  [node]
  (let [expr (:form node)]
    {:expr expr
     :source-expression (:source (meta expr))
     :location (node-location node)}))

(defn- annotate-generic
  [ctx node]
  (assoc (base/annotate-children ctx node) :type at/Dyn))

(defn- annotate-dispatch
  [ctx node]
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
      (ab/schema->type schema))))

(defn- apply-type-override
  [annotated ctx node]
  (if-let [override (resolve-skeptic-type ctx node)]
    (aapi/with-type annotated override)
    annotated))

(defn annotate-node
  [ctx node]
  (let [ctx (assoc ctx :recurse annotate-node)]
    (abl/with-error-context (node-error-context node)
      (-> (annotate-dispatch ctx node)
          (apply-type-override ctx node)
          abr/strip-derived-types))))

(defn annotate-ast
  ([dict ast]
   (annotate-ast dict ast {}))
  ([dict ast {:keys [locals name ns assumptions accessor-summaries]}]
   (annotate-node {:dict (or dict {})
                   :locals (or locals {})
                   :assumptions (vec assumptions)
                   :recur-targets {}
                   :name name
                   :ns ns
                   :accessor-summaries (or accessor-summaries {})}
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

(defn analyze-form
  ([form]
   (analyze-form form {}))
  ([form {:keys [locals ns source-file]}]
   (let [target-ns (target-ns ns)
         env (binding [*ns* target-ns]
               (analyze-env target-ns locals source-file))]
     (binding [*ns* target-ns]
       (ana.jvm/analyze form env)))))

(defn annotate-form-loop
  ([dict form]
   (annotate-form-loop dict form {}))
  ([dict form opts]
   (annotate-ast dict
                 (analyze-form form opts)
                 opts)))
