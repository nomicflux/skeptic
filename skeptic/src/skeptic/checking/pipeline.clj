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
            [skeptic.checking.state :as cstate]
            [skeptic.cljs.analyzer-driver :as cljs-driver]
            [skeptic.file :as file]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.malli-spec.collect :as malli-collect]
            [skeptic.malli-spec.collect.cljs :as malli-collect-cljs]
            [skeptic.provenance :as prov]
            [skeptic.schema.collect.cljs :as schema-collect-cljs]
            [skeptic.schema.discovery :as discovery]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.typed-decls.malli :as typed-decls.malli]
            [skeptic.analysis.predicate-descriptor :as pd])
  (:import [java.io File]))

(defn- file-extension
  [^File f]
  (when f
    (let [n (.getName f)
          i (.lastIndexOf n ".")]
      (when (pos? i) (subs n (inc i))))))

(defn lang-of-source-file
  "`.cljs` → :cljs. `.cljc` → :both. Anything else (.clj or no source-file) → :clj.

  Two-arity form applies `--cljs-disable`: `:cljs` and `:both` collapse to
  `:clj` (i.e. drop the `:cljs` reader-conditional branch from `.cljc`).
  Pure `.cljs` files are expected to be filtered at the discovery layer in
  `core/check-project` before reaching here."
  ([source-file]
   (case (file-extension source-file)
     "cljs" :cljs
     "cljc" :both
     :clj))
  ([opts source-file]
   (let [lang (lang-of-source-file source-file)]
     (if (and (:cljs-disable opts) (#{:cljs :both} lang))
       :clj
       lang))))

(defn- needs-jvm-load?
  [opts source-file]
  (#{:clj :both} (lang-of-source-file opts source-file)))

(defn- enrich-conditional-type
  [t accessor-summaries]
  (let [walk #(enrich-conditional-type % accessor-summaries)]
    (cond
      (not (at/semantic-type-value? t)) t

      (or (at/dyn-type? t)
          (at/bottom-type? t)
          (at/numeric-dyn-type? t)
          (at/ground-type? t)
          (at/type-var-type? t)
          (at/placeholder-type? t)
          (at/inf-cycle-type? t)) t

      (at/conditional-type? t)
      (let [ns-sym (:declared-in (prov/of t))]
        (update t :branches
                (fn [bs] (mapv (fn [[pred typ slot3]]
                                 [pred (walk typ)
                                  (pd/predicate-form->descriptor slot3 ns-sym accessor-summaries)])
                               bs))))

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
  [dict accessor-summaries]
  (reduce-kv (fn [m k t] (assoc m k (enrich-conditional-type t accessor-summaries)))
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
        (cond-> {:kind :unary-map-projection
                 :path [{:value key}]}
          (and (some? default-node)
               (ac/literal-map-key? default-node))
          (assoc :default (ac/literal-node-value default-node)))))))

(defn- keyword-get-classifier-summary
  [param-sym body]
  (when (keyword-call? body)
    (let [[arg] (aapi/call-args body)]
      (when-let [summary (get-call-summary param-sym arg)]
        (assoc summary
               :kind :unary-map-projection
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
  (if (and (= :unary-map-projection (:kind summary))
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
                    {:kind :unary-map-projection
                     :path [{:value kw}]}))

                (and (= :invoke (aapi/node-op body))
                     (ac/get-call? (aapi/call-fn-node body)))
                (let [[target key-node] (aapi/call-args body)
                      kw (literal-keyword key-node)]
                  (when (and kw
                             target
                             (aapi/local-node? target)
                             (= param-sym (:form target)))
                    {:kind :unary-map-projection
                     :path [{:value kw}]}))

                (and (= :static-call (aapi/node-op body))
                     (ac/static-get-call? body))
                (let [[target key-node] (aapi/call-args body)
                      kw (literal-keyword key-node)]
                  (when (and kw
                             target
                             (aapi/local-node? target)
                             (= param-sym (:form target)))
                    {:kind :unary-map-projection
                     :path [{:value kw}]}))

                :else
                (or (keyword-get-classifier-summary param-sym body)
                    (when-let [case-node (body-as-classifier-case-node body)]
                      (when-let [path (discriminant-projection-path (:test case-node) param-sym)]
                        (let [[pairs default] (case-pairs+default case-node)]
                          {:kind :unary-map-projection
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
  [dict ns-sym source-file accessor-summaries cljs-state lang expr]
  (case lang
    :clj
    (aa/annotate-form-loop dict
                           (cf/normalize-check-form expr)
                           {:ns ns-sym
                            :source-file (cf/source-file-path source-file)
                            :accessor-summaries accessor-summaries
                            :lang :clj})
    :cljs
    (let [ns-ast (some-> cljs-state (get source-file) :ns-ast)
          _ (when-not ns-ast
              (throw (ex-info "cljs analyze-source-expr requires cljs-state with :ns-ast for source-file"
                              {:ns ns-sym :source-file (some-> source-file str)})))
          ast (cljs-driver/analyze-form ns-ast (cf/normalize-check-form expr))]
      (aa/annotate-ast dict ast {:ns ns-sym
                                 :accessor-summaries accessor-summaries
                                 :lang :cljs}))))

(defn- accumulate-analysis
  [dict ns-sym source-file acc cljs-state lang expr]
  (let [analyzed (analyze-source-expr dict ns-sym source-file (:accessor-summaries acc) cljs-state lang expr)
        entry (when-let [entry (analyzed-def-entry ns-sym analyzed)]
                (enrich-summary-with-declared-output dict entry))]
    (cond-> (update acc :analyzed conj analyzed)
      entry (update :entries conj entry)
      (:summary entry) (assoc-in [:accessor-summaries (:sym entry)] (:summary entry)))))

(defn- run-analyze-source-exprs
  [dict ns-sym source-file exprs accessor-summaries cljs-state lang]
  (let [{:keys [analyzed entries]}
        (reduce #(accumulate-analysis dict ns-sym source-file %1 cljs-state lang %2)
                {:analyzed [] :entries [] :accessor-summaries accessor-summaries}
                exprs)]
    {:analysis-dict dict
     :analyzed analyzed
     :resolved analyzed
     :resolved-defs (into {} (map (juxt :sym :entry)) entries)}))

(s/defn analyze-source-exprs :- s/Any
  [dict ns-sym source-file exprs
   accessor-summaries :- {s/Any s/Any}
   cljs-state :- {s/Any s/Any}
   lang :- (s/enum :clj :cljs :both)]
  (run-analyze-source-exprs dict ns-sym source-file exprs accessor-summaries cljs-state lang))

(s/defschema AccessorPathElem
  ;; get-call-summary / accessor-summary-from-body emit `{:value Keyword}`.
  ;; case-classifier branch emits `amo/exact-key-query` maps with `:value Any`,
  ;; `:kind :exact`, `:prov`, `:source-form`, plus ::amo/map-key-query.
  {:value                       s/Any
   s/Keyword                    s/Any})

(s/defschema AccessorSummary
  ;; Producer-faithful shape. `:default` and `:cases` values are heterogeneous:
  ;; literal map-key values from get-call-summary, or raw source forms from
  ;; the case-classifier branch (`(:form (:then ...))`). `:values` from
  ;; `enrich-summary-with-declared-output` are ValueT inner :value, which the
  ;; semantic type layer leaves as `s/Any`.
  {:kind                                (s/enum :unary-identity :unary-map-projection)
   (s/optional-key :path)               [AccessorPathElem]
   (s/optional-key :default)            s/Any
   (s/optional-key :values)             [s/Any]
   (s/optional-key :cases)              {s/Any s/Any}
   (s/optional-key :result-transform)   (s/enum :keyword)})

(s/defschema AccessorSummaries
  {s/Symbol AccessorSummary})

(s/defn ^:private collect-accessor-summaries-for-ns :- AccessorSummaries
  [dict :- {s/Symbol ats/SemanticType}
   ns-sym :- s/Symbol
   source-file :- (s/maybe File)
   exprs :- [(s/pred seq?)]
   seed-summaries :- AccessorSummaries
   cljs-state :- {s/Any s/Any}
   lang :- (s/enum :clj :cljs :both)]
  (reduce
   (fn [acc expr]
     (let [analyzed (analyze-source-expr dict ns-sym source-file acc cljs-state lang expr)]
       (if-let [entry (some->> analyzed
                               (analyzed-def-entry ns-sym)
                               (enrich-summary-with-declared-output dict))]
         (if-let [summary (:summary entry)]
           (assoc acc (:sym entry) summary)
           acc)
         acc)))
   seed-summaries
   exprs))

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
                                      :location (assoc source-body-location :source (:source report) :lang (:lang report))}]
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
         :lang (:lang report)
         :cast-summary (:cast-summary report)
         :cast-diagnostics (:cast-diagnostics report)
         :errors (:errors report)}))))

(defn- location-with-source
  [location source lang]
  (assoc (or location {}) :source source :lang lang))

(s/defn input-cast-result :- s/Any
  [enclosing-form node :- aas/AnnotatedNode error-groups]
  (let [display (cf/display-expr node)
        primary-group (first error-groups)]
    {:blame (:expr display)
     :report-kind :input
     :source-expression (:source-expression display)
     :expanded-expression (:expanded-expression display)
     :location (location-with-source (:location display) (:source primary-group) (:lang primary-group))
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
            matched (ca/match-up-arglists arg-nodes expected-arglist actual-arglist)]
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
   :location {:file (cf/source-file-path source-file) :source :inferred :lang :clj}
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
   :location (location-with-source (cf/form-location source-file source-form) :inferred :clj)
   :enclosing-form (enclosing-form ns-sym source-form)
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(defn- resolved-defs-provenance
  [resolved-defs]
  (into {} (map (fn [[sym entry]] [sym (prov/of (:type entry))])) resolved-defs))

(s/defn check-ns-form :- s/Any
  [dict ignore-body :- #{s/Symbol} ns :- s/Symbol source-file source-form
   accessor-summaries :- {s/Any s/Any}
   cljs-state :- {s/Any s/Any}
   lang :- (s/enum :clj :cljs :both)
   opts]
  (try
    (let [{:keys [resolved resolved-defs]} (analyze-source-exprs dict ns source-file [source-form]
                                                                 accessor-summaries cljs-state lang)]
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

(def ^:private form-ref-roles
  "Roles whose source forms bridge.descriptors/raw->descriptor can normalize.
  Other Plumatic roles (defprotocol, defrecord-class/factory) are discovered
  for var-provs but their parent forms produce no useful form-ref descriptor."
  #{:s/defn :s/def :s/defschema})

(defn- populate-form-refs!
  [^java.util.IdentityHashMap form-refs ns-sym discovery-out]
  (doseq [[_qsym {:keys [role form declared-sym]}] (:declarations discovery-out)
          :when (form-ref-roles role)
          :let [v (ns-resolve (the-ns ns-sym) declared-sym)]
          :when (var? v)]
    (.put form-refs v form)))

(defn- ns-var-provs
  "Per-namespace pre-admission {qsym → Provenance}. Plumatic anchors :schema
  with declared Var meta; Malli anchors :malli on every Var the Malli admission
  collector would admit (registry ∪ :malli/schema Var-meta walk).
  Each branch is gated by the corresponding intake-disable opt so the provs
  map cannot announce a stream that produced no admission."
  [opts ns-sym discovery-out]
  (let [schema-provs
        (when (and (not (:plumatic-disable opts)) discovery-out)
          (into {}
                (keep (fn [[qsym {:keys [declared-sym]}]]
                        (when-let [v (ns-resolve (the-ns ns-sym) declared-sym)]
                          (when (var? v)
                            [qsym (prov/make-provenance :schema qsym ns-sym (meta v))]))))
                (:declarations discovery-out)))
        malli-provs
        (when-not (:malli-disable opts)
          (try
            (into {}
                  (map (fn [qsym]
                         [qsym (prov/make-provenance :malli qsym ns-sym nil)]))
                  (malli-collect/malli-admitted-qsyms ns-sym))
            (catch Exception _ {})))]
    (merge schema-provs malli-provs)))

(defn- clj-namespace-dict
  [opts ns-sym source-file var-provs]
  (require ns-sym)
  (let [discovery-out (or (get-in opts [:skeptic/project-discovery ns-sym])
                          (when source-file (discovery/discover ns-sym source-file)))
        form-refs (java.util.IdentityHashMap.)
        opts' (cond-> (assoc opts :skeptic/lang :clj)
                source-file (assoc :skeptic/source-file source-file))]
    (when discovery-out
      (populate-form-refs! form-refs ns-sym discovery-out))
    (binding [*ns* (the-ns ns-sym)
              ab/*form-refs* form-refs
              ab/*var-provs* var-provs]
      (let [schema-result (typed-decls/typed-ns-results opts' ns-sym)
            malli-result (typed-decls.malli/typed-ns-malli-results opts' ns-sym)]
        (typed-decls/merge-type-dicts [schema-result malli-result (native-result)])))))

(defn- cljs-namespace-dict
  [opts ns-sym source-file cljs-state var-provs]
  (let [per-file (some-> cljs-state (get source-file))
        {:keys [ns-ast asts]} per-file
        _ (when-not per-file
            (throw (ex-info "cljs namespace-dict requires cljs-state with per-file entry (intake invariant)"
                            {:ns ns-sym :source-file (some-> source-file str)})))
        top-asts (or asts [])
        form-refs (java.util.IdentityHashMap.)
        schema-result (if (:plumatic-disable opts)
                        (typed-decls/convert-collected ns-sym :cljs {:entries {} :errors []})
                        (binding [ab/*form-refs* form-refs
                                  ab/*var-provs* var-provs]
                          (typed-decls/convert-collected
                           ns-sym :cljs
                           (schema-collect-cljs/ns-schema-results-cljs
                            ns-ast source-file ns-sym top-asts))))
        malli-result (if (:malli-disable opts)
                       (typed-decls.malli/convert-collected ns-sym :cljs {:entries {} :errors []})
                       (binding [ab/*form-refs* form-refs
                                 ab/*var-provs* var-provs]
                         (typed-decls.malli/convert-collected
                          ns-sym :cljs
                          (malli-collect-cljs/ns-malli-spec-results-cljs
                           source-file ns-sym top-asts))))]
    (typed-decls/merge-type-dicts [schema-result malli-result (native-result)])))

(s/defn namespace-dict :- s/Any
  [opts ns-sym :- s/Symbol source-file lang cljs-state var-provs]
  (case lang
    :clj  (clj-namespace-dict opts ns-sym source-file var-provs)
    :cljs (cljs-namespace-dict opts ns-sym source-file cljs-state var-provs)
    :both (typed-decls/merge-type-dicts
           [(clj-namespace-dict opts ns-sym source-file var-provs)
            (cljs-namespace-dict opts ns-sym source-file cljs-state var-provs)])))

(defn project-discovery
  [nss-with-source-files]
  (reduce (fn [acc [ns-sym source-file]]
            (if-not source-file
              acc
              (assoc acc ns-sym (discovery/discover ns-sym source-file))))
          {}
          nss-with-source-files))

(defn- preload-namespaces
  "Require every JVM-loadable namespace (.clj or .cljc) once up front. cljs-only
  namespaces (.cljs) skip the require — they have no JVM Var surface — and
  flow straight to :loaded. Partitions into {:loaded [[ns-sym source-file
  lang] ...] :load-failures {ns-sym {:source-file f :exception e}}}.
  Subsequent project-state phases iterate :loaded only, so the-ns/ns-resolve
  calls there cannot trip on an unloaded namespace and load failures are
  surfaced as discovery warnings instead of bogus per-namespace exception
  findings.

  `opts` carries `--cljs-disable`; when set, `.cljc` files store `lang :clj`
  in the triple so downstream passes drop the cljs branch."
  [opts nss-with-source-files]
  (reduce (fn [acc [ns-sym source-file]]
            (let [lang (lang-of-source-file opts source-file)]
              (cond
                (not (needs-jvm-load? opts source-file))
                (update acc :loaded conj [ns-sym source-file lang])

                :else
                (try
                  (require ns-sym)
                  (update acc :loaded conj [ns-sym source-file lang])
                  (catch Throwable e
                    (assoc-in acc [:load-failures ns-sym]
                              {:source-file source-file :exception e}))))))
          {:loaded [] :load-failures {}}
          nss-with-source-files))

(defn preload-cljs-state!
  "Parse and analyze every source-file requiring cljs analysis (.cljs or
  .cljc). Each ns form is parsed (which JVM-loads its `:require-macros`
  namespaces) and its top-level forms are analyzed via the stateless cljs
  analyzer driver. Returns `{File → {:ns-ast ns-ast :asts [ast …]}}` —
  empty when `cljs-disable?` is truthy or `loaded` contains no cljs/cljc
  sources. Each file's analysis is independent and carries no compiler
  state beyond the call boundary."
  [cljs-disable? loaded]
  (if cljs-disable?
    {}
    (let [cljs-files (->> loaded
                          (keep second)
                          (filter #(#{:cljs :both} (lang-of-source-file %)))
                          distinct
                          vec)]
      (reduce (fn [m f] (assoc m f (cljs-driver/analyze-source-file f)))
              {}
              cljs-files))))

(defn project-var-provs
  [opts project-disc]
  (let [type-override-provs
        (if (:plumatic-disable opts)
          {}
          (into {}
                (map (fn [[sym _]]
                       [sym (prov/make-provenance :type-override sym nil nil)]))
                (or (:skeptic/type-overrides opts) {})))]
    (reduce-kv (fn [acc ns-sym discovery-out]
                 (merge acc (ns-var-provs opts ns-sym discovery-out)))
               (merge native-fns/native-fn-provenance type-override-provs)
               project-disc)))

(s/defn project-state :- s/Any
  "Source-of-truth for the project pass: per-ns admission once, dicts merged
  into a project-wide dict; accessor summaries collected per-ns against the
  merged dict; conditional descriptors enriched once on the merged dict.
  Returns {:dict <enriched-merged> :accessor-summaries <merged>
           :per-ns {ns-sym {:ignore-body :errors :provenance}}}.
  Threaded into every per-namespace check so cross-namespace var resolution
  and conditional discriminators ride the same project pass.

  When `loaded` includes `.cljs` or `.cljc` source-files, each is parsed
  and analyzed independently via the stateless cljs analyzer driver and
  the results are carried as a local `cljs-state` map (source-file →
  `{:ns-ast :asts}`) — threaded into per-ns admission and stored on the
  returned `ProjectState`. Per-ns admission/analysis dispatches on the
  per-source-file `lang` carried in each `loaded` triple."
  [opts nss-with-source-files]
  (let [{loaded :loaded load-failures :load-failures}
        (preload-namespaces opts nss-with-source-files)
        load-failures (reduce-kv (fn [m k v] (assoc m k (assoc v :phase :load)))
                                 {} load-failures)
        clj-loaded (filter (fn [[_ _ lang]] (#{:clj :both} lang)) loaded)
        project-disc (project-discovery (mapv (fn [[ns-sym sf _]] [ns-sym sf]) clj-loaded))
        var-provs (project-var-provs opts project-disc)
        cljs-state (preload-cljs-state! (:cljs-disable opts) loaded)
        opts (assoc opts :skeptic/project-discovery project-disc)
        {:keys [per-ns-admission admission-failures]}
        (reduce (fn [acc [ns-sym source-file lang]]
                  (try
                    (assoc-in acc [:per-ns-admission ns-sym]
                              (namespace-dict opts ns-sym source-file lang cljs-state var-provs))
                    (catch Throwable e
                      (assoc-in acc [:admission-failures ns-sym]
                                {:source-file source-file :exception e :phase :admission}))))
                {:per-ns-admission {} :admission-failures {}}
                loaded)
        merged-dict (reduce-kv (fn [m _ {:keys [dict]}] (merge m dict))
                               {}
                               per-ns-admission)
        {:keys [accessor-summaries accessor-failures]}
        (reduce
         (fn [acc [ns-sym source-file lang]]
           (if (and source-file ns-sym (contains? per-ns-admission ns-sym)
                    (#{:clj :both} lang))
             (try
               (binding [*ns* (the-ns ns-sym)]
                 (update acc :accessor-summaries
                         #(collect-accessor-summaries-for-ns merged-dict ns-sym source-file
                                                             (ns-exprs source-file) %
                                                             cljs-state :clj)))
               (catch Throwable e
                 (assoc-in acc [:accessor-failures ns-sym]
                           {:source-file source-file :exception e :phase :accessors})))
             acc))
         {:accessor-summaries {} :accessor-failures {}}
         loaded)
        enriched-dict (enrich-conditional-descriptors merged-dict accessor-summaries)
        per-ns (reduce-kv (fn [m k v]
                            (assoc m k (select-keys v [:ignore-body :errors :provenance])))
                          {}
                          per-ns-admission)
        per-ns-failures (merge load-failures admission-failures accessor-failures)]
    (cstate/->ProjectState enriched-dict accessor-summaries per-ns per-ns-failures
                           cljs-state project-disc var-provs)))

(defn- prepare-namespace
  [project-state ns-sym _source-file]
  (let [per-ns-entry (get-in project-state [:per-ns ns-sym])]
    (when-not (and project-state per-ns-entry)
      (throw (ex-info "prepare-namespace requires project-state with per-ns entry (intake invariant)"
                      {:ns ns-sym :have-project-state? (some? project-state)})))
    (let [{:keys [dict accessor-summaries]} project-state
          {:keys [ignore-body errors provenance]} per-ns-entry]
      {:dict dict
       :ignore-body ignore-body
       :accessor-summaries accessor-summaries
       :errors errors
       :provenance provenance})))

(s/defn check-s-expr :- s/Any
  [s-expr project-state {:keys [ns source-file check-def] :as opts}]
  (binding [*ns* (the-ns ns)]
    (let [{:keys [dict ignore-body accessor-summaries]} (prepare-namespace project-state ns source-file)
          cljs-state (:cljs-state project-state)
          lang (lang-of-source-file opts source-file)
          source-form (find-source-form s-expr source-file check-def)
          {:keys [results]} (check-ns-form dict ignore-body ns source-file source-form
                                           accessor-summaries cljs-state lang opts)]
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

(defn- finding-dedup-key
  "Identity for cross-language deduping: same form/location/blame/rule hits
  both passes for .cljc files. Strips :lang from location so a finding
  produced under :clj and :cljs collapses into one with merged :lang.
  Excludes expected-type/actual-type because their inner :prov :lang
  necessarily differs between passes (the analyzer-side ctx anchor) — using
  rule + blame + location is enough discrimination at one source line."
  [r]
  [(:report-kind r)
   (:phase r)
   (:blame r)
   (:rule r)
   (:enclosing-form r)
   (some-> (:location r) (dissoc :lang))])

(defn- combine-finding-langs
  [findings]
  (let [s (reduce (fn [acc f]
                    (let [l (get-in f [:location :lang])]
                      (cond
                        (nil? l) acc
                        (set? l) (into acc l)
                        :else (conj acc l))))
                  #{}
                  findings)]
    (cond
      (empty? s) nil
      (= 1 (count s)) (first s)
      :else s)))

(defn- dedup-cljc-findings
  [findings]
  (->> findings
       (group-by finding-dedup-key)
       (mapv (fn [[_k group]]
               (let [merged-lang (combine-finding-langs group)]
                 (if (and merged-lang (some #(get-in % [:location :lang]) group))
                   (assoc-in (first group) [:location :lang] merged-lang)
                   (first group)))))))

(defn- with-jvm-ns
  [needs-jvm? ns-sym thunk]
  (if needs-jvm?
    (binding [*ns* (the-ns ns-sym)] (thunk))
    (thunk)))

(defn- read-pass-results
  [dict ignore-body ns source-file accessor-summaries cljs-state lang form-opts]
  (with-open [reader (file/pushback-reader source-file)]
    (loop [acc {:results [] :provenance {}}]
      (let [{:keys [kind form exception]} (next-checkable-form reader)]
        (case kind
          :eof acc
          :form (let [{form-results :results form-prov :provenance}
                      (check-ns-form dict ignore-body ns source-file form
                                     accessor-summaries cljs-state lang form-opts)]
                  (recur (-> acc
                             (update :results into form-results)
                             (update :provenance merge form-prov))))
          :read-error (recur (update acc :results conj
                                     (read-exception-result source-file exception))))))))

(s/defn check-ns :- s/Any
  ([ns :- s/Symbol source-file opts]
   (check-ns (:project-state opts) ns source-file (dissoc opts :project-state)))
  ([project-state ns :- s/Symbol source-file form-opts]
   (let [{:keys [dict ignore-body accessor-summaries errors provenance]}
         (prepare-namespace project-state ns source-file)
         lang (lang-of-source-file form-opts source-file)
         passes (case lang :both [:clj :cljs] [lang])
         cljs-state (:cljs-state project-state)
         pass-results (mapv (fn [pass-lang]
                              (let [needs-jvm? (#{:clj :both} pass-lang)]
                                (with-jvm-ns needs-jvm? ns
                                  #(read-pass-results dict ignore-body ns source-file
                                                      accessor-summaries cljs-state pass-lang
                                                      form-opts))))
                            passes)
         form-findings (vec (mapcat :results pass-results))
         deduped (if (= :both lang)
                   (dedup-cljc-findings form-findings)
                   form-findings)
         merged-prov (-> (reduce (fn [m r] (merge m (:provenance r)))
                                 {}
                                 pass-results)
                         (merge (or provenance {})))]
     {:results (vec (concat (vec errors) deduped))
      :provenance merged-prov})))

(s/defn load-exception-result :- s/Any
  [ns-sym :- s/Symbol e :- Throwable]
  {:report-kind :exception
   :phase :load
   :blame ns-sym
   :enclosing-form ns-sym
   :namespace ns-sym
   :location {:source :inferred :lang :clj}
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(s/defn check-namespace :- s/Any
  "Owns the full per-namespace run: :load (require), :declaration + :read + :expression
  (typed declarations and form checking). Returns
  {:results [...] :provenance {sym → Provenance}}."
  ([opts ns-sym :- s/Symbol source-file]
   (check-namespace (:project-state opts) ns-sym source-file (dissoc opts :project-state)))
  ([project-state ns-sym :- s/Symbol source-file form-opts]
   (try
     (check-ns project-state ns-sym source-file form-opts)
     (catch Exception e
       {:results [(load-exception-result ns-sym e)]
        :provenance {}}))))
