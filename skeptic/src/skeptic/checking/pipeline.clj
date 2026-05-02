(ns skeptic.checking.pipeline
  (:require [schema.core :as s]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.bridge :as ab]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.native-fns :as native-fns]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.types.schema :as ats]
            [skeptic.analysis.value :as av]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.checking.ast :as ca]
            [skeptic.checking.form :as cf]
            [skeptic.file :as file]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.provenance :as prov]
            [skeptic.schema.collect :as collect]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.typed-decls.malli :as typed-decls.malli]
            [skeptic.analysis.predicate-descriptor :as pd])
  (:import [java.io File]))

(defn- enrich-conditional-type
  [t ns-sym accessor-summaries]
  (let [walk #(enrich-conditional-type % ns-sym accessor-summaries)]
    (cond
      (not (at/semantic-type-value? t)) t

      (at/conditional-type? t)
      (update t :branches
              (fn [bs] (mapv (fn [[pred typ slot3]]
                               [pred (walk typ)
                                (pd/predicate-form->descriptor slot3 ns-sym accessor-summaries)])
                             bs)))

      (at/maybe-type? t) (update t :inner walk)
      (at/optional-key-type? t) (update t :inner walk)
      (at/var-type? t) (update t :inner walk)
      (at/value-type? t) (update t :inner walk)
      (at/forall-type? t) (update t :body walk)
      (at/sealed-dyn-type? t) (update t :ground walk)
      (at/union-type? t) (update t :members #(mapv walk %))
      (at/intersection-type? t) (update t :members #(mapv walk %))
      (at/vector-type? t) (update t :items #(mapv walk %))
      (at/seq-type? t) (update t :items #(mapv walk %))
      (at/set-type? t) (update t :members #(into #{} (map walk) %))
      (at/fn-method-type? t) (-> t (update :inputs #(mapv walk %))
                                 (update :output walk))
      (at/fun-type? t) (update t :methods #(mapv walk %))
      (at/map-type? t) (update t :entries
                               #(into {} (map (fn [[k v]] [(walk k) (walk v)])) %))
      :else t)))

(defn- enrich-conditional-descriptors
  [dict ns-sym accessor-summaries]
  (reduce-kv (fn [m k t] (assoc m k (enrich-conditional-type t ns-sym accessor-summaries)))
             {}
             dict))

(defn- discriminant-projection-path
  [discriminant param-sym]
  (let [origin (:origin discriminant)]
    (when (and (= :map-key-lookup (:kind origin))
               (= param-sym (:sym (:root origin))))
      (:path origin))))

(s/defn ^:private case-test-literal-nodes :- s/Any
  [t :- aas/AnnotatedNode]
  (cond
    (aapi/const-or-quote? t) [t]
    (aapi/case-test-node? t) [(:test t)]
    :else (let [raw (or (:tests t) (:test t))]
            (when raw (if (vector? raw) raw [raw])))))

(s/defn ^:private case-pairs+default :- s/Any
  [case-node :- aas/CaseNode]
  (let [tests (:tests case-node)
        thens (:thens case-node)
        n (min (count tests) (count thens))
        pairs (into {}
                    (mapcat (fn [i]
                              (let [lits (mapv ac/literal-node-value
                                               (case-test-literal-nodes (nth tests i)))
                                    then-form (:form (:then (nth thens i)))]
                                (map (fn [lit] [lit then-form]) lits))))
                    (range n))]
    [pairs (:form (:default case-node))]))

(s/defn ^:private body-as-classifier-case-node :- (s/maybe aas/AnnotatedNode)
  [body :- aas/AnnotatedNode]
  (let [body (aapi/unwrap-with-meta body)]
    (case (aapi/node-op body)
      :case body
      :let (body-as-classifier-case-node (:body body))
      :do (body-as-classifier-case-node (:ret body))
      nil)))

(defn- keyword-call?
  [node]
  (and (= :invoke (aapi/node-op node))
       (contains? '#{keyword clojure.core/keyword}
                  (ac/resolved-call-sym (aapi/call-fn-node node)))))

(defn- get-call-summary
  [param-sym node]
  (when (or (and (= :invoke (aapi/node-op node))
                 (ac/get-call? (aapi/call-fn-node node)))
            (and (= :static-call (aapi/node-op node))
                 (ac/static-get-call? node)))
    (let [[target key-node default-node] (aapi/call-args node)
          key (when (ac/literal-map-key? key-node)
                (ac/literal-node-value key-node))]
      (when (and (keyword? key)
                 target
                 (aapi/local-node? target)
                 (= param-sym (:form target)))
        (cond-> {:path [{:value key}]}
          (and (some? default-node)
               (ac/literal-map-key? default-node))
          (assoc :default (ac/literal-node-value default-node)))))))

(defn- keyword-get-classifier-summary
  [param-sym body]
  (when (keyword-call? body)
    (let [[arg] (aapi/call-args body)]
      (when-let [summary (get-call-summary param-sym arg)]
        (assoc summary
               :kind :unary-map-classifier
               :result-transform :keyword)))))

(defn- finite-values
  [type]
  (cond
    (at/value-type? type)
    [(:value type)]

    (at/union-type? type)
    (let [values (mapcat finite-values (:members type))]
      (when (= (count values) (count (:members type)))
        values))

    :else nil))

(defn- declared-output-values
  [dict sym]
  (when-let [type (get dict sym)]
    (when (at/fun-type? type)
      (let [values (mapcat (comp finite-values at/fn-method-output)
                           (at/fun-methods type))]
        (when (seq values)
          (vec (distinct values)))))))

(defn- enrich-summary-with-declared-output
  [dict {:keys [sym summary] :as entry}]
  (if (and (= :unary-map-classifier (:kind summary))
           (not (contains? summary :values)))
    (if-let [values (declared-output-values dict sym)]
      (assoc entry :summary (assoc summary :values values))
      entry)
    entry))

(s/defn analyzed-def-entry :- s/Any
  [ns-sym :- (s/maybe s/Symbol) analyzed :- aas/AnnotatedNode]
  (letfn [(literal-keyword [node]
            (when (ac/literal-map-key? node)
              (let [value (ac/literal-node-value node)]
                (when (keyword? value)
                  value))))
          (accessor-summary-from-body [param-sym body]
            (let [body (aapi/unwrap-with-meta body)]
              (cond
                (and (= :local (aapi/node-op body))
                     (= param-sym (aapi/node-form body)))
                {:kind :unary-identity}

                (= :keyword-invoke (aapi/node-op body))
                (let [target (:target body)
                      kw-node (:keyword body)
                      kw (literal-keyword kw-node)]
                  (when (and kw
                             target
                             (aapi/local-node? target)
                             (= param-sym (:form target)))
                    {:kind :unary-map-accessor
                     :kw kw}))

                (and (= :invoke (aapi/node-op body))
                     (ac/get-call? (aapi/call-fn-node body)))
                (let [[target key-node] (aapi/call-args body)
                      kw (literal-keyword key-node)]
                  (when (and kw
                             target
                             (aapi/local-node? target)
                             (= param-sym (:form target)))
                    {:kind :unary-map-accessor
                     :kw kw}))

                (and (= :static-call (aapi/node-op body))
                     (ac/static-get-call? body))
                (let [[target key-node] (aapi/call-args body)
                      kw (literal-keyword key-node)]
                  (when (and kw
                             target
                             (aapi/local-node? target)
                             (= param-sym (:form target)))
                    {:kind :unary-map-accessor
                     :kw kw}))

                :else
                (or (keyword-get-classifier-summary param-sym body)
                    (when-let [case-node (body-as-classifier-case-node body)]
                      (when-let [path (discriminant-projection-path (:test case-node) param-sym)]
                        (let [[pairs default] (case-pairs+default case-node)]
                          {:kind :unary-map-classifier
                           :path path
                           :cases pairs
                           :default default})))))))
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
          value-node (some-> analyzed aapi/unwrap-with-meta aapi/def-value-node)
          summary (some-> value-node def-accessor-summary)]
      (when (and sym entry)
        {:sym sym
         :entry (aapi/strip-derived-types entry)
         :summary summary}))))

(defn- analyze-source-expr
  [dict ns-sym source-file accessor-summaries expr]
  (aa/annotate-form-loop dict
                         (cf/normalize-check-form expr)
                         {:ns ns-sym
                          :source-file (cf/source-file-path source-file)
                          :accessor-summaries accessor-summaries}))

(defn- accumulate-analysis
  [dict ns-sym source-file acc expr]
  (let [analyzed (analyze-source-expr dict ns-sym source-file (:accessor-summaries acc) expr)
        entry (when-let [entry (analyzed-def-entry ns-sym analyzed)]
                (enrich-summary-with-declared-output dict entry))]
    (cond-> (update acc :analyzed conj analyzed)
      entry (update :entries conj entry)
      (:summary entry) (assoc-in [:accessor-summaries (:sym entry)] (:summary entry)))))

(defn- run-analyze-source-exprs
  [dict ns-sym source-file exprs accessor-summaries]
  (let [{:keys [analyzed entries accessor-summaries]}
        (reduce #(accumulate-analysis dict ns-sym source-file %1 %2)
                {:analyzed [] :entries [] :accessor-summaries accessor-summaries}
                exprs)]
    {:analysis-dict dict
     :analyzed analyzed
     :resolved analyzed
     :resolved-defs (into {} (map (juxt :sym :entry)) entries)
     :accessor-summaries accessor-summaries}))

(s/defn analyze-source-exprs :- s/Any
  ([dict ns-sym source-file exprs]
   (run-analyze-source-exprs dict ns-sym source-file exprs {}))
  ([dict ns-sym source-file exprs {:keys [accessor-summaries]}]
   (run-analyze-source-exprs dict ns-sym source-file exprs (or accessor-summaries {}))))

(s/defn method-output-type :- ats/SemanticType
  [method]
  (let [{:keys [body output-type]} (aapi/method-result-type method)
        tagged-output (when-let [tag (aapi/node-tag body)]
                        (av/class->type (prov/of output-type) tag))]
    (if (incm/unknown-output-type? (ato/normalize output-type))
      (ato/normalize (or tagged-output output-type))
      (ato/normalize output-type))))

(s/defn def-output-results :- s/Any
  [dict ns-sym source-file source-form enclosing-form node :- aas/AnnotatedNode]
  (let [declared-t (when (aapi/def-node? node) (ac/lookup-type dict ns-sym node))
        init-node (when (aapi/def-node? node)
                    (some-> node aapi/def-init-node ca/unwrap-with-meta))
        methods (when (and init-node (= :fn (aapi/node-op init-node)))
                  (aapi/function-methods init-node))]
    (when (and declared-t (seq methods))
      (->> (map vector methods (cf/defn-decls source-form))
           (keep (fn [[method decl]]
                   (let [expected-output (some-> (at/select-method (at/fun-methods declared-t) (count (:params method)))
                                                 :output
                                                 ato/normalize)
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
                                      :location (assoc source-body-location :source (:source report))}]
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

(s/defn input-error-group :- s/Any
  [expr arg-node :- (s/maybe aas/AnnotatedNode) expected :- ats/SemanticType actual :- ats/SemanticType]
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
         :source (:source report)
         :cast-summary (:cast-summary report)
         :cast-diagnostics (:cast-diagnostics report)
         :errors (:errors report)}))))

(defn- location-with-source
  [location source]
  (assoc (or location {}) :source source))

(s/defn input-cast-result :- s/Any
  [enclosing-form node :- aas/AnnotatedNode error-groups]
  (let [display (cf/display-expr node)
        primary-group (first error-groups)]
    {:blame (:expr display)
     :report-kind :input
     :source-expression (:source-expression display)
     :expanded-expression (:expanded-expression display)
     :location (location-with-source (:location display) (:source primary-group))
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

(s/defn match-s-exprs :- s/Any
  [enclosing-form node :- aas/AnnotatedNode keep-empty :- s/Bool]
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

(s/defn enclosing-form :- s/Any
  [ns-sym :- (s/maybe s/Symbol) source-form]
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

(s/defn check-resolved-form :- s/Any
  [dict ignore-body :- #{s/Symbol} ns-sym :- s/Symbol source-file source-form analyzed :- aas/AnnotatedNode {:keys [keep-empty remove-context debug] :or {keep-empty false} :as opts}]
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

(s/defn ns-exprs :- s/Any
  [source-file]
  (with-open [reader (file/pushback-reader source-file)]
    (->> (repeatedly #(file/try-read reader))
         (take-while some?)
         (remove file/is-ns-block?)
         doall)))

(s/defn read-exception-result :- s/Any
  [source-file e :- Throwable]
  {:report-kind :exception
   :phase :read
   :blame (cf/source-file-path source-file)
   :source-expression nil
   :location {:file (cf/source-file-path source-file) :source :inferred}
   :enclosing-form nil
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(s/defn next-checkable-form :- s/Any
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

(s/defn expression-exception-result :- s/Any
  [ns-sym :- (s/maybe s/Symbol) source-file source-form e :- Throwable]
  {:report-kind :exception
   :phase :expression
   :blame source-form
   :source-expression (cf/form-source source-form)
   :location (location-with-source (cf/form-location source-file source-form) :inferred)
   :enclosing-form (enclosing-form ns-sym source-form)
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(defn- resolved-defs-provenance
  [resolved-defs]
  (into {} (map (fn [[sym entry]] [sym (prov/of (:type entry))])) resolved-defs))

(s/defn check-ns-form :- s/Any
  [dict ignore-body :- #{s/Symbol} ns :- s/Symbol source-file source-form opts]
  (try
    (let [accessor-summaries (or (:accessor-summaries opts) {})
          {:keys [resolved resolved-defs]} (analyze-source-exprs dict ns source-file [source-form]
                                                                 {:accessor-summaries accessor-summaries})]
      {:results (vec (check-resolved-form dict
                                          ignore-body
                                          ns
                                          source-file
                                          source-form
                                          (first resolved)
                                          opts))
       :provenance (resolved-defs-provenance resolved-defs)})
    (catch Exception e
      {:results [(expression-exception-result ns source-file source-form e)]
       :provenance {}})))

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

(s/defn namespace-dict :- s/Any
  [opts ns-sym :- s/Symbol source-file]
  (require ns-sym)
  (let [form-refs (java.util.IdentityHashMap.)]
    (when source-file
      (collect/build-form-refs! form-refs ns-sym source-file))
    (binding [*ns* (the-ns ns-sym)
              ab/*form-refs* form-refs]
      (let [schema-result (typed-decls/typed-ns-results opts ns-sym)
            malli-result (typed-decls.malli/typed-ns-malli-results opts ns-sym)
            merged (typed-decls/merge-type-dicts [schema-result malli-result (native-result)])]
        merged))))

(defn- preanalyzed-ns-dict
  [dict ns-sym source-file]
  (if (and source-file ns-sym)
    (let [exprs (ns-exprs source-file)
          {:keys [resolved-defs accessor-summaries]} (analyze-source-exprs dict ns-sym source-file exprs)]
      {:dict (merge (into {} (map (fn [[sym _]] [sym (or (:type (get resolved-defs sym))
                                                         (at/Dyn (prov/inferred {:name sym :ns ns-sym})))]))
                          accessor-summaries)
                    dict)
       :accessor-summaries accessor-summaries})
    {:dict dict
     :accessor-summaries {}}))

(defn- prepare-namespace
  [opts ns-sym source-file]
  (let [{:keys [dict ignore-body errors provenance]} (namespace-dict opts ns-sym source-file)]
    (binding [*ns* (the-ns ns-sym)]
      (let [{dict :dict accessor-summaries :accessor-summaries}
            (preanalyzed-ns-dict dict ns-sym source-file)
            dict (enrich-conditional-descriptors dict ns-sym accessor-summaries)]
        {:dict dict
         :ignore-body ignore-body
         :accessor-summaries accessor-summaries
         :errors errors
         :provenance provenance}))))

(s/defn check-s-expr :- s/Any
  [s-expr {:keys [ns source-file check-def] :as opts}]
  (binding [*ns* (the-ns ns)]
    (let [{:keys [dict ignore-body accessor-summaries]} (prepare-namespace opts ns source-file)
          source-form (find-source-form s-expr source-file check-def)
          form-opts (assoc opts :accessor-summaries accessor-summaries)
          {:keys [results]} (check-ns-form dict ignore-body ns source-file source-form form-opts)]
      (vec results))))

(defmacro block-in-ns
  [_ns ^File file & body]
  `(let [contents# (slurp ~file)
         ns-dec# (read-string contents#)
         current-namespace# (str ~*ns*)]
     (eval ns-dec#)
     (let [res# (do ~@body)]
       (clojure.core/in-ns (symbol current-namespace#))
       res#)))

(s/defn check-ns :- s/Any
  [ns :- s/Symbol source-file opts]
  (let [{:keys [dict ignore-body accessor-summaries errors provenance]} (prepare-namespace opts ns source-file)
        form-opts (assoc opts :accessor-summaries accessor-summaries)]
    (binding [*ns* (the-ns ns)]
      (with-open [reader (file/pushback-reader source-file)]
        (loop [acc {:results (vec errors) :provenance {}}]
          (let [{:keys [kind form exception]} (next-checkable-form reader)]
            (case kind
              :eof (update acc :provenance #(merge % (or provenance {})))
              :form (let [{form-results :results form-prov :provenance} (check-ns-form dict ignore-body ns source-file form form-opts)]
                      (recur (-> acc
                                 (update :results into form-results)
                                 (update :provenance merge form-prov))))
              :read-error (recur (update acc :results conj (read-exception-result source-file exception))))))))))

(s/defn load-exception-result :- s/Any
  [ns-sym :- s/Symbol e :- Throwable]
  {:report-kind :exception
   :phase :load
   :blame ns-sym
   :enclosing-form ns-sym
   :namespace ns-sym
   :location {:source :inferred}
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(s/defn check-namespace :- s/Any
  "Owns the full per-namespace run: :load (require), :declaration + :read + :expression
  (typed declarations and form checking). Returns
  {:results [...] :provenance {sym → Provenance}}."
  [opts ns-sym :- s/Symbol source-file]
  (try
    (check-ns ns-sym source-file opts)
    (catch Exception e
      {:results [(load-exception-result ns-sym e)]
       :provenance {}})))
