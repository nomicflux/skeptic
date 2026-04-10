(ns skeptic.analysis.annotate
  (:require [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [skeptic.analysis.annotate.base :as aab]
            [skeptic.analysis.annotate.control :as aactl]
            [skeptic.analysis.annotate.data :as aad]
            [skeptic.analysis.annotate.fn :as aaf]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.invoke :as aai]
            [skeptic.analysis.annotate.jvm :as aaj]
            [skeptic.analysis.annotate.match :as aam]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.native-fns :as anf]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.types :as at]))

(defn node-location
  [node]
  (select-keys (meta (:form node)) [:file :line :column :end-line :end-column]))

(defn node-error-context
  [node]
  (let [expr (:form node)
        source-expression (:source (meta expr))]
    {:expr expr
     :source-expression source-expression
     :location (node-location node)}))

(defn annotate-node
  [ctx node]
  (let [ctx (assoc ctx :recurse annotate-node)]
    (abl/with-error-context (node-error-context node)
      (abr/strip-derived-types
       (case (:op node)
         :binding (aab/annotate-binding ctx node)
         :const (aab/annotate-const ctx node)
         :def (aad/annotate-def ctx node)
         :do (aactl/annotate-do ctx node)
         :fn (aaf/annotate-fn ctx node)
         :fn-method (aaf/annotate-fn-method ctx node)
         :if (aactl/annotate-if ctx node)
         :case (aam/annotate-case ctx node)
         :instance-call (aaj/annotate-instance-call ctx node)
         :invoke (aai/annotate-invoke ctx node)
         :let (aactl/annotate-let ctx node)
         :loop (aactl/annotate-loop ctx node)
         :local (aab/annotate-local ctx node)
         :map (aad/annotate-map ctx node)
         :new (aad/annotate-new ctx node)
         :quote (aad/annotate-quote ctx node)
         :recur (aactl/annotate-recur ctx node)
         :set (aad/annotate-set ctx node)
         :static-call (aaj/annotate-static-call ctx node)
         :the-var (aab/annotate-var-like ctx node)
         :throw (aad/annotate-throw ctx node)
         :try (aad/annotate-try ctx node)
         :var (aab/annotate-var-like ctx node)
         :vector (aad/annotate-vector ctx node)
         :with-meta (aad/annotate-with-meta ctx node)
         (assoc (aab/annotate-children ctx node)
                :type at/Dyn))))))

(defn annotate-ast
  ([dict ast]
   (annotate-ast dict ast {}))
  ([dict ast {:keys [locals name ns assumptions]}]
   (annotate-node {:dict dict
                   :locals (into {}
                                 (map (fn [[sym entry]]
                                        [sym (an/normalize-entry entry)]))
                                 locals)
                   :assumptions (vec assumptions)
                   :recur-targets {}
                   :name name
                   :ns ns}
                  ast)))

(defn analyze-form
  ([form]
   (analyze-form form {}))
  ([form {:keys [locals ns source-file]}]
   (let [target-ns (or (some-> ns the-ns) *ns*)
         env (binding [*ns* target-ns]
               (cond-> (assoc (ta/empty-env)
                              :ns (ns-name target-ns)
                              :locals (into {}
                                            (map-indexed (fn [idx sym]
                                                           [sym (aapi/synthetic-binding-node idx sym)]))
                                            (keys locals)))
                 source-file
                 (assoc :file source-file)))]
     (binding [*ns* target-ns]
       (ana.jvm/analyze form env)))))

(defn annotate-form-loop
  ([dict form]
   (annotate-form-loop dict form {}))
  ([dict form opts]
   (annotate-ast (merge anf/native-fn-dict dict)
                 (analyze-form form opts)
                 opts)))
