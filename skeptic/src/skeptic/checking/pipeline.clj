(ns skeptic.checking.pipeline
  (:require [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.native-fns :as native-fns]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.checking.ast :as ca]
            [skeptic.checking.form :as cf]
            [skeptic.file :as file]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.typed-decls.malli :as typed-decls.malli])
  (:import [java.io File]))

(defn analyzed-def-entry
  [ns-sym analyzed]
  (letfn [(literal-keyword [node]
            (when (ac/literal-map-key? node)
              (let [value (ac/literal-node-value node)]
                (when (keyword? value)
                  value))))
          (accessor-summary-from-body [param-sym body]
            (let [body (aapi/unwrap-with-meta body)]
              (cond
                (= :keyword-invoke (aapi/node-op body))
                (let [target (:target body)
                      kw-node (:keyword body)
                      kw (literal-keyword kw-node)]
                  (when (and kw
                             (= :local (:op target))
                             (= param-sym (:form target)))
                    {:kind :unary-map-accessor
                     :kw kw}))

                (and (= :invoke (aapi/node-op body))
                     (ac/get-call? (aapi/call-fn-node body)))
                (let [[target key-node] (aapi/call-args body)
                      kw (literal-keyword key-node)]
                  (when (and kw
                             (= :local (:op target))
                             (= param-sym (:form target)))
                    {:kind :unary-map-accessor
                     :kw kw}))

                (and (= :static-call (aapi/node-op body))
                     (ac/static-get-call? body))
                (let [[target key-node] (aapi/call-args body)
                      kw (literal-keyword key-node)]
                  (when (and kw
                             (= :local (:op target))
                             (= param-sym (:form target)))
                    {:kind :unary-map-accessor
                     :kw kw}))

                :else nil)))
          (def-accessor-summary [node]
            (when (= :fn (aapi/node-op node))
              (let [methods (aapi/function-methods node)]
                (when (= 1 (count methods))
                  (let [method (first methods)
                        params (:params method)]
                    (when (= 1 (count params))
                      (accessor-summary-from-body (:form (first params))
                                                 (aapi/method-body method))))))))]
    (let [[sym entry] (aapi/analyzed-def-entry ns-sym analyzed)
          value-node (some-> analyzed aapi/unwrap-with-meta aapi/def-value-node)]
      (when (and sym entry)
        [sym
         (cond-> entry
           true aapi/strip-derived-types
           true (as-> normalized
                      (if-let [summary (some-> value-node def-accessor-summary)]
                        (assoc normalized :accessor-summary summary)
                        normalized)))]))))

(defn analyze-source-exprs
  [dict ns-sym source-file exprs]
  (let [analyzed (mapv (fn [expr]
                         (aa/annotate-form-loop dict
                                                (cf/normalize-check-form expr)
                                                {:ns ns-sym
                                                 :source-file (cf/source-file-path source-file)}))
                       exprs)]
    {:analysis-dict dict
     :analyzed analyzed
     :resolved analyzed
     :resolved-defs (into {}
                          (keep #(analyzed-def-entry ns-sym %))
                          analyzed)}))

(defn method-output-type
  [method]
  (let [{:keys [body output-type]} (aapi/method-result-type method)
        tagged-output (some-> (aapi/node-tag body) av/class->type)]
    (if (incm/unknown-output-type? (ato/normalize-type output-type))
      (ato/normalize-type (or tagged-output output-type))
      (ato/normalize-type output-type))))

(defn def-output-results
  [dict ns-sym source-file source-form enclosing-form node]
  (let [declared-t (ac/lookup-type dict ns-sym node)
        init-node (some-> node aapi/def-init-node ca/unwrap-with-meta)
        methods (aapi/function-methods init-node)]
    (when (and declared-t (seq methods))
      (->> (map vector methods (cf/defn-decls source-form))
           (keep (fn [[method decl]]
                   (let [expected-output (some-> (at/select-method (at/fun-methods declared-t) (count (:params method)))
                                                 :output
                                                 ato/normalize-type)
                         source-body (cf/method-source-body decl)
                         actual-output (method-output-type method)
                         body (aapi/method-body method)
                         source-body-location
                         (cf/merge-location
                          (when source-file (cf/form-location source-file decl))
                          (when source-body
                            (select-keys (meta source-body)
                                         [:file :line :column :end-line :end-column])))
                         report (when expected-output
                                  (inrep/output-cast-report
                                   {:expr (aapi/node-name node)
                                    :arg (or source-body (aapi/node-form body))}
                                   expected-output
                                   actual-output))]
                     (when-not (:ok? report)
                       (let [source-expression (cf/form-source source-body)
                             display {:expr (or source-body (aapi/node-form body))
                                      :source-expression source-expression
                                      :expanded-expression (when (or (not= source-body (aapi/node-form body))
                                                                     (and source-expression
                                                                          (not= source-expression (pr-str (aapi/node-form body)))))
                                                             (aapi/node-form body))
                                      :location source-body-location}]
                         {:blame (:expr display)
                          :report-kind :output
                          :source-expression (:source-expression display)
                          :expanded-expression (:expanded-expression display)
                          :location (:location display)
                          :enclosing-form enclosing-form
                          :path nil
                          :context {:local-vars (ca/local-vars-context body)
                                    :refs (if (ca/call-node? body)
                                            (ca/call-refs body)
                                            [])}
                          :blame-side (:blame-side report)
                          :blame-polarity (:blame-polarity report)
                          :rule (:rule report)
                          :expected-type (:expected-type report)
                          :actual-type (:actual-type report)
                          :cast-summary (:cast-summary report)
                          :cast-diagnostics (:cast-diagnostics report)
                          :errors (:errors report)})))))))))

(defn input-error-group
  [expr arg-node expected actual]
  (let [arg-expr (if (some? arg-node)
                   (aapi/node-form arg-node)
                   arg-node)
        report (inrep/cast-report {:expr expr
                                   :arg arg-expr}
                                  expected
                                  actual)]
    (when (seq (:errors report))
      (let [arg-display (when arg-node
                          (cf/display-expr arg-node))]
        {:focus arg-expr
         :focus-source (:source-expression arg-display)
         :blame-side (:blame-side report)
         :blame-polarity (:blame-polarity report)
         :rule (:rule report)
         :expected-type (:expected-type report)
         :actual-type (:actual-type report)
         :cast-summary (:cast-summary report)
         :cast-diagnostics (:cast-diagnostics report)
         :errors (:errors report)}))))

(defn input-cast-result
  [enclosing-form node error-groups]
  (let [display (cf/display-expr node)
        primary-group (first error-groups)]
    {:blame (:expr display)
     :report-kind :input
     :source-expression (:source-expression display)
     :expanded-expression (:expanded-expression display)
     :location (:location display)
     :enclosing-form enclosing-form
     :focuses (ca/distinctv (keep :focus error-groups))
     :focus-sources (ca/distinctv (keep :focus-source error-groups))
     :path nil
     :blame-side (or (:blame-side primary-group) :none)
     :blame-polarity (or (:blame-polarity primary-group) :none)
     :rule (:rule primary-group)
     :expected-type (:expected-type primary-group)
     :actual-type (:actual-type primary-group)
     :cast-summary (:cast-summary primary-group)
     :cast-diagnostics (vec (mapcat :cast-diagnostics error-groups))
     :context {:local-vars (ca/local-vars-context node)
               :refs (ca/call-refs node)}
     :errors (vec (mapcat :errors error-groups))}))

(defn match-s-exprs
  [enclosing-form node keep-empty]
  (when (ca/call-node? node)
    (let [expected-arglist (vec (aapi/call-expected-argtypes node))
          actual-arglist (vec (aapi/call-actual-argtypes node))]
      (assert (not (or (nil? expected-arglist) (nil? actual-arglist)))
              (format "Arglists must not be nil: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (assert (>= (count actual-arglist) (count expected-arglist))
              (format "Actual should have at least as many elements as expected: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (let [arg-nodes (if (aapi/recur-node? node) (aapi/recur-args node) (aapi/call-args node))
            matched (cf/spy :matched-arglists
                            (ca/match-up-arglists arg-nodes
                                                  (cf/spy :expected-argtypes expected-arglist)
                                                  (cf/spy :actual-argtypes actual-arglist)))]
        (if-let [error-groups (seq (keep (fn [[arg-node expected actual]]
                                           (input-error-group (aapi/node-form node) arg-node expected actual))
                                         matched))]
          (input-cast-result enclosing-form node error-groups)
          (when keep-empty
            (input-cast-result enclosing-form node [])))))))

(defn enclosing-form
  [ns-sym source-form]
  (if (and (seq? source-form)
           (symbol? (second source-form))
           (symbol? (first source-form)))
    (ac/qualify-symbol ns-sym (second source-form))
    source-form))

(defn- ignored-body-def?
  [ignore-body ns-sym source-form]
  (when (and (seq? source-form)
             (symbol? (first source-form))
             (symbol? (second source-form)))
    (let [qualified-sym (ac/qualify-symbol ns-sym (second source-form))]
      (boolean (or (contains? ignore-body qualified-sym)
                   (some-> qualified-sym resolve meta :skeptic/ignore-body)
                   (some-> qualified-sym resolve meta :skeptic/opaque))))))

(defn check-resolved-form
  [dict ignore-body ns-sym source-file source-form analyzed {:keys [keep-empty remove-context debug] :as opts}]
  (let [enclosing-form (enclosing-form ns-sym source-form)
        ignored? (ignored-body-def? ignore-body ns-sym source-form)
        results (if ignored?
                  []
                  (->> (aapi/annotated-nodes analyzed)
                       (mapcat (fn [node]
                                 (abl/with-error-context (cf/node-error-context node enclosing-form)
                                   (let [call-result (match-s-exprs enclosing-form node keep-empty)]
                                     (concat (when call-result
                                               [call-result])
                                             (or (def-output-results dict
                                                                     ns-sym
                                                                     source-file
                                                                     source-form
                                                                     enclosing-form
                                                                     node)
                                                 []))))))
                       vec))
        debug-records (when debug
                        [{:report-kind    :debug-form
                          :ns             ns-sym
                          :source-file    source-file
                          :source-form    source-form
                          :enclosing-form enclosing-form
                          :ignored-body?  (boolean ignored?)
                          :dict           dict
                          :analyzed       analyzed
                          :opts           opts
                          :raw-results    results}])
        filtered (cond->> results
                   (not keep-empty)
                   (remove (comp empty? :errors))

                   remove-context
                   (map #(dissoc % :context)))]
    (vec (concat debug-records filtered))))

(defn ns-exprs
  [source-file]
  (with-open [reader (file/pushback-reader source-file)]
    (->> (repeatedly #(file/try-read reader))
         (take-while some?)
         (remove file/is-ns-block?)
         doall)))

(defn read-exception-result
  [source-file e]
  {:report-kind :exception
   :phase :read
   :blame (cf/source-file-path source-file)
   :source-expression nil
   :location {:file (cf/source-file-path source-file)}
   :enclosing-form nil
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(defn next-checkable-form
  [reader]
  (try
    (loop []
      (let [source-form (file/try-read reader)]
        (cond
          (nil? source-form)
          {:kind :eof}

          (file/is-ns-block? source-form)
          (recur)

          :else
          {:kind :form
           :form source-form})))
    (catch Exception e
      {:kind :read-error
       :exception e})))

(defn expression-exception-result
  [ns-sym source-file source-form e]
  {:report-kind :exception
   :phase :expression
   :blame source-form
   :source-expression (cf/form-source source-form)
   :location (cf/form-location source-file source-form)
   :enclosing-form (enclosing-form ns-sym source-form)
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(defn check-ns-form
  [dict ignore-body ns source-file source-form opts]
  (try
    (let [{:keys [resolved]} (analyze-source-exprs dict ns source-file [source-form])]
      (vec (check-resolved-form dict
                                ignore-body
                                ns
                                source-file
                                source-form
                                (first resolved)
                                opts)))
    (catch Exception e
      [(expression-exception-result ns source-file source-form e)])))

(defn- find-source-form
  "Locate the top-level form for the def name in source-file by name match.
  Throws if not found — no index-0 fallback."
  [s-expr source-file check-def]
  (let [def-name (or (when check-def (-> check-def name symbol))
                     (when (seq? s-expr) (second s-expr)))]
    (if (and def-name source-file)
      (let [exprs (ns-exprs source-file)]
        (or (first (filter #(= def-name (second %)) exprs))
            (throw (ex-info (str "No top-level form found for: " def-name)
                            {:def-name def-name}))))
      (cf/normalize-check-form s-expr))))

(defn- native-result []
  {:dict native-fns/native-fn-dict
   :provenance native-fns/native-fn-provenance
   :ignore-body #{}
   :errors []})

(defn namespace-dict
  [opts ns-sym]
  (require ns-sym)
  (let [schema-result (typed-decls/typed-ns-results opts ns-sym)
        malli-result (typed-decls.malli/typed-ns-malli-results opts ns-sym)
        merged (typed-decls/merge-type-dicts [schema-result malli-result (native-result)])]
    merged))

(defn- preanalyzed-ns-dict
  [dict ns-sym source-file]
  (if (and source-file ns-sym)
    (let [exprs (ns-exprs source-file)
          resolved-defs (:resolved-defs (analyze-source-exprs dict ns-sym source-file exprs))]
      (reduce (fn [acc [sym resolved-entry]]
                (if-let [summary (:accessor-summary resolved-entry)]
                  (let [t (or (:type resolved-entry) at/Dyn)]
                    (assoc acc sym {:type t :accessor-summary summary}))
                  acc))
              dict
              resolved-defs))
    dict))

(defn check-s-expr
  [s-expr {:keys [keep-empty remove-context ns source-file check-def] :as opts}]
  (binding [*ns* (the-ns ns)]
    (let [{:keys [dict ignore-body]} (namespace-dict opts ns)
          source-form (find-source-form s-expr source-file check-def)
          analysis-dict (preanalyzed-ns-dict dict ns source-file)
          {:keys [resolved]} (analyze-source-exprs analysis-dict ns source-file [source-form])]
      (vec (check-resolved-form dict
                                ignore-body
                                ns
                                source-file
                                source-form
                                (first resolved)
                                {:keep-empty keep-empty
                                 :remove-context remove-context})))))

(defmacro block-in-ns
  [_ns ^File file & body]
  `(let [contents# (slurp ~file)
         ns-dec# (read-string contents#)
         current-namespace# (str ~*ns*)]
     (eval ns-dec#)
     (let [res# (do ~@body)]
       (clojure.core/in-ns (symbol current-namespace#))
       res#)))

(defn check-ns
  [ns source-file opts]
  (let [{:keys [dict ignore-body]} (namespace-dict opts ns)]
    (binding [*ns* (the-ns ns)]
      (with-open [reader (file/pushback-reader source-file)]
        (loop [results []]
          (let [{:keys [kind form exception]} (next-checkable-form reader)]
            (case kind
              :eof results
              :form (recur (into results
                                 (check-ns-form dict ignore-body ns source-file form opts)))
              :read-error (conj results (read-exception-result source-file exception)))))))))

(defn load-exception-result
  [ns-sym e]
  {:report-kind :exception
   :phase :load
   :blame ns-sym
   :enclosing-form ns-sym
   :namespace ns-sym
   :location {}
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(defn check-namespace
  "Owns the full per-namespace run: :load (require), :declaration + :read + :expression
  (typed declarations and form checking). Returns a vector of localized result maps."
  [opts ns-sym source-file]
  (try
    (let [{:keys [errors]} (namespace-dict opts ns-sym)
          check-results (check-ns ns-sym source-file opts)]
      (vec (concat errors check-results)))
    (catch Exception e
      [(load-exception-result ns-sym e)])))
