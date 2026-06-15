(ns skeptic.checking.pipeline
  (:require [clojure.edn :as edn]
            [schema.core :as s]
            [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.annotate.api :as aapi]
            [skeptic.analysis.annotate.schema :as aas]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.analysis.call-kinds.projection :as ck-projection]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.class-oracle :as class-oracle]
            [skeptic.analysis.bridge.descriptors :as descriptors]
            [skeptic.analysis.native-fns :as native-fns]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.types :as at]
            [skeptic.analysis.value :as av]
            [skeptic.provenance.schema :as provs]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.checking.ast :as ca]
            [skeptic.checking.form :as cf]
            [skeptic.checking.opts :as copts]
            [skeptic.checking.state :as cstate]
            [skeptic.cljs.topo :as topo]
            [skeptic.file :as file]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.inconsistence.report :as inrep]
            [skeptic.malli-spec.collect :as malli-collect]
            [skeptic.provenance :as prov]
            [skeptic.schema.discovery :as discovery]
            [skeptic.typed-decls :as typed-decls]
            [skeptic.analysis.predicate-descriptor :as pd]
            [skeptic.worker.client :as wc]
            [skeptic.worker.wire :as wire])
  (:import [java.io File]))

(s/defschema AccessorPathElem
  ;; get-call-summary / accessor-summary-from-body emit `{:value Keyword}`.
  ;; case-classifier branch emits `amo/exact-key-query` maps with `:value Any`,
  ;; `:kind :exact`, `:prov`, `:source-form`, plus ::amo/map-key-query.
  {:value                       s/Any
   s/Keyword                    s/Any})

(s/defschema FiniteValue
  "A literal value extracted from a `ValueT` Type's `:value` slot — i.e., the
  finite values a Skeptic-known function can return when its output type is a
  union of `s/eq`/`:=` constants. The realistic dispatch case is enum-like
  primitives. Extending to collection-valued literals (`(s/eq [:a :b])`)
  requires adding the collection variant here, not widening callers to
  `s/Any`."
  (s/cond-pre s/Keyword s/Num s/Str s/Symbol s/Bool Character (s/eq nil)))

(s/defschema AccessorSummary
  ;; Producer-faithful shape. `:default` and `:cases` values are heterogeneous:
  ;; literal map-key values from get-call-summary, or raw source forms from
  ;; the case-classifier branch (`(:form (:then ...))`). `:values` from
  ;; `enrich-summary-with-declared-output` are `ValueT` inner :value typed by
  ;; `FiniteValue`.
  {:kind                                (s/enum :unary-identity :unary-map-projection)
   (s/optional-key :path)               [AccessorPathElem]
   (s/optional-key :default)            s/Any
   (s/optional-key :values)             [FiniteValue]
   (s/optional-key :cases)              {s/Any s/Any}
   (s/optional-key :result-transform)   (s/enum :keyword)})

(s/defschema AccessorSummaries
  {s/Symbol AccessorSummary})

(defn- file-extension
  [^File f]
  (when f
    (let [n (.getName f)
          i (.lastIndexOf n ".")]
      (when (pos? i) (subs n (inc i))))))

(defn lang-of-source-file
  "`.cljs` → :cljs. `.cljc` → :both. Anything else (.clj or no source-file) → :clj.

  Two-arity form applies `:cljs-disable` (set unless `--cljs-enable` was
  passed): `:cljs` and `:both` collapse to
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

(defn- lang-of-namespace-source
  [opts ns-sym source-file]
  (let [lang (lang-of-source-file opts source-file)]
    (if (and (= :both lang)
             (contains? (:cljs-only-namespaces opts) ns-sym))
      :cljs
      lang)))

(defn- inert-conditional-type?
  [t]
  (or (at/dyn-type? t)
      (at/bottom-type? t)
      (at/numeric-dyn-type? t)
      (at/ground-type? t)
      (at/type-var-type? t)
      (at/placeholder-type? t)
      (at/inf-cycle-type? t)))

(defn- unary-recurse-field
  [t]
  (cond
    (at/maybe-type? t)        :inner
    (at/optional-key-type? t) :inner
    (at/var-type? t)          :inner
    (at/value-type? t)        :inner
    (at/forall-type? t)       :body
    (at/sealed-dyn-type? t)   :ground))

(defn- n-ary-recurse-field
  [t]
  (cond
    (at/union-type? t)        :members
    (at/intersection-type? t) :members
    (at/seq-type? t)          :items
    (at/fun-type? t)          :methods))

(s/defn ^:private enrich-conditional-branches :- at/SemanticType
  [t :- at/SemanticType
   walk :- (s/pred fn?)
   accessor-summaries :- (s/recursive #'AccessorSummaries)]
  (let [ns-sym (:declared-in (prov/of t))]
    (update t :branches
            (fn [bs]
              (mapv (fn [b]
                      (at/->ConditionalBranch
                       (:pred b)
                       (walk (:type b))
                       (pd/predicate-form->descriptor (:pred-form b) ns-sym accessor-summaries)
                       (:pred-form b)))
                    bs)))))

(s/defn ^:private enrich-fn-method-type :- at/SemanticType
  [t :- at/SemanticType
   walk :- (s/pred fn?)]
  (-> t (update :inputs #(mapv walk %))
        (update :output walk)))

(s/defn ^:private enrich-map-entries :- at/SemanticType
  [t :- at/SemanticType
   walk :- (s/pred fn?)]
  (update t :entries
          #(into {} (map (fn [[k v]] [(walk k) (walk v)])) %)))

(s/defn ^:private enrich-conditional-type :- at/SemanticType
  [t :- at/SemanticType
   accessor-summaries :- (s/recursive #'AccessorSummaries)]
  (let [walk    #(enrich-conditional-type % accessor-summaries)
        unary-k (unary-recurse-field t)
        nary-k  (n-ary-recurse-field t)]
    (cond
      (inert-conditional-type? t)       t
      (at/conditional-type? t)          (enrich-conditional-branches t walk accessor-summaries)
      unary-k                           (update t unary-k walk)
      nary-k                            (update t nary-k #(mapv walk %))
      (at/set-type? t)                  (update t :members #(into #{} (map walk) %))
      (at/fn-method-type? t)            (enrich-fn-method-type t walk)
      (at/map-type? t)                  (enrich-map-entries t walk)
      :else                             t)))

(s/defn ^:private enrich-conditional-descriptors :- {s/Symbol at/SemanticType}
  [dict :- {s/Symbol at/SemanticType}
   accessor-summaries :- (s/recursive #'AccessorSummaries)]
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
  (when-let [[key target] (ck-projection/literal-key-projection node)]
    (when (and (aapi/local-node? target)
               (= param-sym (:form target)))
      (let [default-node (nth (aapi/call-args node) 2 nil)]
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

(s/defn ^:private finite-values :- [FiniteValue]
  [type :- at/SemanticType]
  (cond
    (at/value-type? type)
    [(:value type)]

    (at/union-type? type)
    (let [values (mapcat finite-values (:members type))]
      (if (= (count values) (count (:members type)))
        (vec values)
        []))

    :else []))

(s/defn ^:private declared-output-values :- [FiniteValue]
  [dict :- {s/Symbol at/SemanticType}
   sym :- s/Symbol]
  (if-let [type (get dict sym)]
    (if (at/fun-type? type)
      (vec (distinct (mapcat (comp finite-values :output)
                             (:methods type))))
      [])
    []))

(s/defschema PipelineDefEntry
  "Output of pipeline-local `analyzed-def-entry` and input/output of
  `enrich-summary-with-declared-output`. The `:entry` field reuses the
  strict `aapi/AnalyzedDefEntry`, so `:entry`'s `:type` is mandatory."
  {(s/required-key :sym)     s/Symbol
   (s/required-key :entry)   aapi/AnalyzedDefEntry
   (s/required-key :summary) (s/maybe AccessorSummary)})

(s/defn ^:private maybe-enrich-summary :- (s/maybe AccessorSummary)
  [dict    :- {s/Symbol at/SemanticType}
   sym     :- s/Symbol
   summary :- (s/maybe AccessorSummary)]
  (if (and summary
           (= :unary-map-projection (:kind summary))
           (not (contains? summary :values)))
    (let [values (declared-output-values dict sym)]
      (if (seq values)
        (assoc summary :values (vec values))
        summary))
    summary))

(s/defn ^:private enrich-summary-with-declared-output :- PipelineDefEntry
  [dict :- {s/Symbol at/SemanticType}
   entry :- PipelineDefEntry]
  (update entry :summary (partial maybe-enrich-summary dict (:sym entry))))

(s/defn analyzed-def-entry :- (s/maybe PipelineDefEntry)
  [ns-sym :- (s/maybe s/Symbol) analyzed :- aas/AnnotatedNode]
  (letfn [(literal-keyword [node]
            (when (ac/literal-map-key? node)
              (let [value (ac/literal-node-value node)]
                (when (keyword? value)
                  value))))
          (accessor-summary-from-body [param-sym body]
            (let [body (aapi/unwrap-with-meta body)
                  [proj-kw proj-target] (ck-projection/literal-key-projection body)]
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

                (and proj-kw
                     (aapi/local-node? proj-target)
                     (= param-sym (:form proj-target)))
                {:kind :unary-map-projection
                 :path [{:value proj-kw}]}

                :else
                (or (keyword-get-classifier-summary param-sym body)
                    (when-let [case-node (body-as-classifier-case-node body)]
                      (when-let [path (discriminant-projection-path (:test case-node) param-sym)]
                        (let [[pairs default] (case-pairs+default case-node)]
                          {:kind :unary-map-projection
                           :path path
                           :cases pairs
                           :default default})))))))
          (def-accessor-summary [def-node]
            (let [methods (aapi/def-fn-methods def-node)]
              (when (= 1 (count methods))
                (let [method (first methods)
                      params (:params method)]
                  (when (= 1 (count params))
                    (accessor-summary-from-body (:form (first params))
                                                (aapi/method-body method)))))))]
    (let [[sym entry] (aapi/analyzed-def-entry ns-sym analyzed)
          def-node (aapi/unwrap-with-meta analyzed)
          summary (def-accessor-summary def-node)]
      (when (and sym entry)
        {:sym sym
         :entry (abr/strip-derived-types entry)
         :summary summary}))))

(s/defn ^:private collect-accessor-summaries-for-ns :- AccessorSummaries
  [dict :- {s/Symbol at/SemanticType}
   ns-sym :- s/Symbol
   analyzed-asts :- [aas/AnnotatedNode]
   seed-summaries :- AccessorSummaries]
  (reduce
   (fn [acc ast]
     (let [analyzed (aa/annotate-ast dict ast {:ns ns-sym :accessor-summaries acc :lang :clj})]
       (if-let [entry (some->> analyzed
                               (analyzed-def-entry ns-sym)
                               (enrich-summary-with-declared-output dict))]
         (if-let [summary (:summary entry)]
           (assoc acc (:sym entry) summary)
           acc)
         acc)))
   seed-summaries
   analyzed-asts))

(s/defn method-output-type :- at/SemanticType
  [method :- aas/AnnotatedNode]
  (let [{:keys [body output-type]} (aapi/method-result-type method)
        tagged-output (when-let [tag (aapi/node-tag body)]
                        (av/class->type (prov/of output-type) tag))]
    (if (incm/unknown-output-type? (ato/normalize output-type))
      (ato/normalize (or tagged-output output-type))
      (ato/normalize output-type))))

(s/defn def-output-results :- s/Any
  [dict ns-sym source-file source-form enclosing-form node :- aas/AnnotatedNode]
  (let [declared-t (when (aapi/def-node? node) (ac/lookup-type dict ns-sym node))
        methods (aapi/def-fn-methods node)]
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
                          :context {:local-vars (aapi/local-vars-context body)
                                    :refs (if (aapi/call-node? body)
                                            (aapi/call-refs body)
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
  [expr arg-node :- (s/maybe aas/AnnotatedNode) expected :- at/SemanticType actual :- at/SemanticType]
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
     :context {:local-vars (aapi/local-vars-context node)
               :refs (aapi/call-refs node)}
     :errors (vec (mapcat :errors error-groups))}))

(s/defn match-s-exprs :- s/Any
  [enclosing-form node :- aas/AnnotatedNode keep-empty :- s/Bool]
  (when (aapi/call-node? node)
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
      ;; Hermetic: the ignore-body set is populated at admission for BOTH
      ;; :skeptic/ignore-body and :skeptic/opaque (no host project-Var resolve).
      (boolean (contains? ignore-body qualified-sym)))))

(s/defn check-resolved-form :- s/Any
  [dict ignore-body :- #{s/Symbol} ns-sym :- s/Symbol source-file source-form
   analyzed :- aas/AnnotatedNode
   {:keys [keep-empty remove-context debug] :or {keep-empty false} :as opts} :- copts/FormCheckOpts]
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

(s/defn read-exception-result :- s/Any
  [source-file lang :- (s/enum :clj :cljs) e :- Throwable]
  {:report-kind :exception
   :phase :read
   :blame (cf/source-file-path source-file)
   :source-expression nil
   :location {:file (cf/source-file-path source-file) :source :inferred :lang lang}
   :enclosing-form nil
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(s/defn expression-exception-result :- s/Any
  [ns-sym :- (s/maybe s/Symbol) source-file source-form e :- Throwable
   lang :- (s/enum :clj :cljs :both)]
  {:report-kind :exception
   :phase :expression
   :blame source-form
   :source-expression (cf/form-source source-form)
   :location (location-with-source (cf/form-location source-file source-form) :inferred lang)
   :enclosing-form (enclosing-form ns-sym source-form)
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(defn- macroexpansion-failure?
  "An analyzer failure whose ex-data declares :clojure.error/phase :macroexpansion
   means the expression's macro contract was violated at compile time (e.g. a
   cljs macro expecting a quoted symbol literal received a runtime local).
   Such expressions cannot be analyzed; gradual typing demotes them to Dyn
   rather than treating the analyzer crash as a Skeptic-emitted exception."
  [^Throwable e]
  (let [data (ex-data e)]
    (or (= :macroexpansion (:clojure.error/phase data))
        (some-> (.getCause e) ex-data :clojure.error/phase (= :macroexpansion)))))

(s/defn analysis-skipped-result :- s/Any
  [ns-sym :- (s/maybe s/Symbol) source-file source-form e :- Throwable
   lang :- (s/enum :clj :cljs :both)]
  {:report-kind :analysis-skipped
   :phase :expression
   :blame source-form
   :source-expression (cf/form-source source-form)
   :location (location-with-source (cf/form-location source-file source-form) :inferred lang)
   :enclosing-form (enclosing-form ns-sym source-form)
   :exception-class (symbol (.getName (class e)))
   :exception-message (or (.getMessage e)
                          (str e))})

(defn- analyzer-failure-result
  "Route an analyzer-side Throwable to the right report-kind. Macroexpansion
   failures (e.g. cljs macros rejecting a runtime arg, or a per-form
   compile-time exception caught worker-side and reconstructed with the
   :macroexpansion phase tag) are gradually-typed as :analysis-skipped;
   other analyzer crashes remain :exception."
  [ns-sym source-file source-form e lang]
  (if (macroexpansion-failure? e)
    (analysis-skipped-result ns-sym source-file source-form e lang)
    (expression-exception-result ns-sym source-file source-form e lang)))

(s/defn ^:private resolved-defs-provenance :- {s/Symbol provs/Provenance}
  [resolved-defs :- {s/Symbol aapi/AnalyzedDefEntry}]
  (into {} (map (fn [[sym entry]] [sym (prov/of (:type entry))])) resolved-defs))

(s/defschema CljsCachedFormEntry
  "Shape of a single cached CLJS form entry under `cljs-state[source-file] :entries`."
  {(s/required-key :source-form) s/Any
   (s/required-key :ast)         (s/maybe aas/AnnotatedNode)
   (s/optional-key :exception)   Throwable
   (s/optional-key :analysis-skipped?) s/Bool
   (s/optional-key :malli-schema) s/Any
   (s/optional-key :plumatic-schema) s/Any
   (s/optional-key :plumatic-var-prov) s/Symbol})

(s/defschema CljsPassResults
  {(s/required-key :results)    [s/Any]
   (s/required-key :provenance) {s/Symbol provs/Provenance}})

(s/defn ^:private resolved-defs-for-analyzed :- {s/Symbol aapi/AnalyzedDefEntry}
  [dict :- {s/Symbol at/SemanticType}
   ns-sym :- s/Symbol
   analyzed :- aas/AnnotatedNode]
  (if-let [{:keys [sym entry]} (some->> (analyzed-def-entry ns-sym analyzed)
                                        (enrich-summary-with-declared-output dict))]
    {sym entry}
    {}))

(s/defn ^:private check-cached-entry :- CljsPassResults
  [dict               :- {s/Symbol at/SemanticType}
   ignore-body        :- #{s/Symbol}
   ns                 :- s/Symbol
   source-file        :- (s/maybe (s/cond-pre File s/Str))
   lang               :- (s/enum :clj :cljs)
   {:keys [source-form ast exception analysis-skipped?]} :- CljsCachedFormEntry
   accessor-summaries :- AccessorSummaries
   form-opts          :- {s/Keyword s/Any}]
  (cond
    exception
    {:results [(analyzer-failure-result ns source-file source-form exception lang)]
     :provenance {}}

    analysis-skipped?
    {:results []
     :provenance {}}

    ast
    (try
      (let [analyzed (aa/annotate-ast dict ast {:ns ns
                                               :accessor-summaries accessor-summaries
                                               :lang lang})
            resolved-defs (resolved-defs-for-analyzed dict ns analyzed)]
        {:results (vec (check-resolved-form dict
                                            ignore-body
                                            ns
                                            source-file
                                            source-form
                                            analyzed
                                            (select-keys form-opts [:keep-empty :remove-context :debug])))
         :provenance (resolved-defs-provenance resolved-defs)})
      (catch Exception e
        {:results [(analyzer-failure-result ns source-file source-form e lang)]
         :provenance {}}))

    :else
    (throw (ex-info "cljs cached entry has neither :ast nor :exception (Phase 1 invariant violated)"
                    {:ns ns :source-file (some-> source-file str) :source-form source-form}))))

(s/defn ^:private cljs-read-pass-results :- CljsPassResults
  [dict               :- {s/Symbol at/SemanticType}
   ignore-body        :- #{s/Symbol}
   ns                 :- s/Symbol
   source-file        :- (s/maybe (s/cond-pre File s/Str))
   accessor-summaries :- AccessorSummaries
   cljs-state         :- {s/Any s/Any}
   cljs-failure       :- (s/maybe Throwable)
   form-opts          :- {s/Keyword s/Any}]
  (if-let [entries (some-> cljs-state (get source-file) :entries)]
    (reduce
     (fn [acc entry]
       (let [{form-results :results form-prov :provenance}
             (try
               (check-cached-entry dict ignore-body ns source-file :cljs entry
                                   accessor-summaries form-opts)
               (catch Exception e
                 {:results [(analyzer-failure-result ns source-file (:source-form entry) e :cljs)]
                  :provenance {}}))]
         (-> acc
             (update :results into form-results)
             (update :provenance merge form-prov))))
     {:results [] :provenance {}}
     entries)
    {:results [(read-exception-result
                source-file :cljs
                (or cljs-failure
                    (ex-info (str "no cljs analysis state for this source-file: "
                                  "the cljs preload recorded neither entries nor "
                                  "a failure — a Skeptic invariant violation")
                             {:ns ns :source-file (some-> source-file str)})))]
     :provenance {}}))

(s/defn ^:private clj-read-pass-results :- CljsPassResults
  [dict               :- {s/Symbol at/SemanticType}
   ignore-body        :- #{s/Symbol}
   ns                 :- s/Symbol
   source-file        :- (s/maybe (s/cond-pre File s/Str))
   accessor-summaries :- AccessorSummaries
   clj-state          :- {s/Any s/Any}
   clj-failure        :- (s/maybe Throwable)
   form-opts          :- {s/Keyword s/Any}]
  (let [{:keys [entries read-failure] :as state-entry} (some-> clj-state (get source-file))]
    (cond
      read-failure
      {:results [(read-exception-result source-file :clj (ex-info read-failure {}))]
       :provenance {}}

      (nil? state-entry)
      {:results [(read-exception-result
                  source-file :clj
                  (or clj-failure
                      (ex-info (str "no clj analysis state for this source-file: "
                                    "the worker stream recorded neither entries "
                                    "nor a failure — a Skeptic invariant violation")
                               {:ns ns :source-file (some-> source-file str)})))]
       :provenance {}}

      :else
      (reduce
       (fn [acc entry]
         (if (file/is-ns-block? (:source-form entry))
           acc
           (let [{form-results :results form-prov :provenance}
                 (try
                   (check-cached-entry dict ignore-body ns source-file :clj entry
                                       accessor-summaries form-opts)
                   (catch Exception e
                     {:results [(analyzer-failure-result ns source-file (:source-form entry) e :clj)]
                      :provenance {}}))]
             (-> acc
                 (update :results into form-results)
                 (update :provenance merge form-prov)))))
       {:results [] :provenance {}}
       (or entries [])))))

(def ^:private form-ref-roles
  "Roles whose source forms bridge.descriptors/raw->descriptor can normalize.
  Other Plumatic roles (defprotocol, defrecord-class/factory) are discovered
  for var-provs but their parent forms produce no useful form-ref descriptor."
  #{:s/defn :s/def :s/defschema})

(def ^:private form-ref-foldable-sources
  #{:schema :malli :type-override})

(defn- form-ref-qsyms
  [discovery-out var-provs]
  (into (set (keys (:declarations discovery-out)))
        (keep (fn [[qsym p]]
                (when (contains? form-ref-foldable-sources (:source p))
                  qsym)))
        var-provs))

(defn- form-refs-for-ns
  [discovery-out var-provs]
  (let [ref-qsyms (form-ref-qsyms discovery-out var-provs)]
    (into {}
          (keep (fn [[qsym {:keys [role form ns]}]]
                  (when (form-ref-roles role)
                    (when-let [descriptor (descriptors/prepare-form-ref
                                           ns
                                           (:aliases discovery-out)
                                           ref-qsyms
                                           form)]
                      [qsym descriptor]))))
          (:declarations discovery-out))))

(defn- collect-user-fn-summaries
  "Walks an ns's discovery output for `s/defn` source forms and returns the
  subset whose body is a recognised path-type-predicate over a destructured
  key. Keyed by qualified-sym. Anything that doesn't fit the recognised
  shape is silently omitted."
  [discovery-out]
  (into {}
        (keep (fn [[qsym {:keys [role form]}]]
                (when (= :s/defn role)
                  (when-let [summary (ac/path-predicate-summary-from-form form)]
                    [qsym summary]))))
        (:declarations discovery-out)))

(defn- ns-var-provs
  "Per-namespace pre-admission {qsym → Provenance}. Plumatic anchors :schema
  with declared Var meta; Malli anchors :malli on every Var the Malli admission
  collector would admit (registry ∪ :malli/schema Var-meta walk).
  Each branch is gated by the corresponding intake-disable opt so the provs
  map cannot announce a stream that produced no admission."
  [opts ns-sym discovery-out entries]
  (let [schema-provs
        (when (and (not (:plumatic-disable opts)) discovery-out)
          (merge
           (into {}
                 (map (fn [[qsym {:keys [form]}]]
                        [qsym (prov/make-provenance :schema qsym ns-sym (meta form) [] :clj)]))
                 (:declarations discovery-out))
           (into {}
                 (keep (fn [{:keys [plumatic-var-prov source-form]}]
                         (when plumatic-var-prov
                           [plumatic-var-prov
                            (prov/make-provenance :schema plumatic-var-prov ns-sym
                                                  (meta source-form) [] :clj)])))
                 entries)))
        malli-provs
        (when-not (:malli-disable opts)
          (let [aliases (discovery/source-form-aliases (mapv :source-form entries))]
            (into {}
                  (map (fn [qsym]
                         [qsym (prov/make-provenance :malli qsym ns-sym nil [] :clj)]))
                  (malli-collect/malli-admitted-qsyms ns-sym aliases entries))))]
    (merge schema-provs malli-provs)))

(defn- preload-namespaces
  "Tag every discovered namespace with its `lang` and pass it through to
  `:loaded`. The project is NEVER required on the host: each namespace is read
  and analyzed on the WORKER (`preload-clj-state!` / `preload-cljs-state!`), and
  a worker read/analysis failure is surfaced there as a `:clj-load-failure` /
  `:cljs-load-failure`. Returns {:loaded [[ns-sym source-file lang] ...]
  :load-failures {}} — the host-load failure map is always empty now.

  `opts` carries `:cljs-disable` (set unless `--cljs-enable` was passed);
  when set, `.cljc` files store `lang :clj`
  in the triple so downstream passes drop the cljs branch."
  [opts nss-with-source-files]
  (reduce (fn [acc [ns-sym source-file]]
            (let [lang (lang-of-namespace-source opts ns-sym source-file)]
              (update acc :loaded conj [ns-sym source-file lang])))
          {:loaded [] :load-failures {}}
          nss-with-source-files))

(defn- reattach-entry-meta
  "Replay the sibling meta vectors the worker shipped (form metadata cannot ride
   as ordinary collection metadata) back onto an entry's `:source-form` and `:ast`, then drop
   the `-meta` carriers so the stored entry keeps its `{:source-form :ast}`
   shape. See `skeptic.worker.wire/apply-form-meta`.

   When the worker shipped a per-form `:exception-class`/`:exception-message`
   (best-effort recovery, project-faithful-load), reconstruct a Throwable
   tagged with `:clojure.error/phase :macroexpansion` so the existing
   `analyzer-failure-result` routing demotes the offending form to Dyn via
   `analysis-skipped-result`."
  [{:keys [source-form source-form-meta ast ast-meta
           exception-class exception-message exception-data] :as entry}]
  (cond-> (dissoc entry :source-form-meta :ast-meta
                  :exception-class :exception-message :exception-data)
    source-form-meta (assoc :source-form (wire/apply-form-meta source-form source-form-meta))
    ast-meta         (assoc :ast (wire/apply-form-meta ast ast-meta))
    exception-class  (assoc :exception (ex-info (str exception-class ": " exception-message)
                                                {:clojure.error/phase :macroexpansion
                                                 :exception-class exception-class
                                                 :exception-message exception-message
                                                 :exception-data exception-data}))))

(defn- process-stream-reply
  "Handle one intermediate reply from the streaming worker op. Reattaches
   entry metadata, stores in clj-state, builds ns-entries and discovery
   for that namespace. A `:shadowed-by` reply (the loaded runtime says
   another file defines this namespace) is recorded in `shadowed-a` and
   contributes no entries: its text is not the namespace's definition.
   Mutates the accumulators in place (atoms). Replies are correlated by
   `:source-file` — one namespace may arrive from several files, and each
   file's reply must land on its own clj-state entry."
  [clj-state-a clj-failures-a ns-entries-a project-disc-a shadowed-a loaded-index reply]
  (let [[ns-sym source-file] (get loaded-index (:source-file reply))]
    (cond
      (:load-error reply)
      (swap! clj-failures-a assoc source-file
             {:exception (ex-info (str (:load-error reply) ": " (:load-error-message reply)) {})})

      (:shadowed-by reply)
      (swap! shadowed-a assoc source-file
             {:ns ns-sym :defined-by (vec (:shadowed-by reply))})

      :else
      (try
        (let [{:keys [entries read-failure]} reply
              reattached (if read-failure
                           {:entries [] :read-failure read-failure}
                           {:entries (mapv reattach-entry-meta entries)})]
          (swap! clj-state-a assoc source-file reattached)
          (let [ns-ents (vec (:entries reattached))]
            (swap! ns-entries-a assoc ns-sym ns-ents)
            (let [forms (mapv :source-form ns-ents)]
              (swap! project-disc-a assoc ns-sym (discovery/discover ns-sym forms)))))
        (catch Throwable e
          ;; Host-side processing of one namespace's reply (meta reattachment,
          ;; discovery) failed: record it as that namespace's failure — it
          ;; surfaces as an exception finding — and keep consuming the stream
          ;; so every other namespace still gets full analysis.
          (swap! clj-state-a dissoc source-file)
          (swap! clj-failures-a assoc source-file
                 {:exception e :phase :reply-processing}))))))

(defn preload-clj-state!
  "Analyze every clj/cljc source-file via the WORKER's streaming
  analyze-namespaces-stream op. As each namespace's complete entries arrive,
  immediately reattach metadata, build ns-entries and discovery. A namespace
  discovered from more than one file is flagged on the wire so the worker can
  report files the project's own load does not select (`:shadowed-files`,
  source-file → {:ns :defined-by}). Returns
  `{:clj-state :clj-load-failures :ns-entries-map :project-disc
  :shadowed-files}`. Empty when `conn` is nil. Under `verbose?`, each
  `:starting?` reply prints a `[skeptic analyze clj]` marker — last marker
  printed names the namespace whose `require`/macroexpansion blocked if the
  worker hangs."
  [conn loaded verbose?]
  (if-not conn
    {:clj-state {} :clj-load-failures {} :ns-entries-map {} :project-disc {}
     :shadowed-files {}}
    (let [clj-triples (into []
                            (filter (fn [[_ sf lang]] (and sf (#{:clj :both} lang))))
                            loaded)
          duplicate-nss (->> clj-triples
                             (map first)
                             frequencies
                             (keep (fn [[ns-sym n]] (when (< 1 n) ns-sym)))
                             set)
          clj-pairs (mapv (fn [[ns-sym sf _]]
                            [(str ns-sym) (str sf) (contains? duplicate-nss ns-sym)])
                          clj-triples)
          loaded-index (into {}
                             (map (fn [[ns-sym sf _]] [(str sf) [ns-sym sf]]))
                             clj-triples)
          clj-state-a (atom {})
          clj-failures-a (atom {})
          ns-entries-a (atom {})
          project-disc-a (atom {})
          shadowed-a (atom {})]
      (when (seq clj-pairs)
        (when verbose?
          (binding [*out* *err*]
            (println (str "[skeptic analyze clj] streaming " (count clj-pairs) " namespaces"))
            (flush)))
        (wc/ask-streaming conn
                          {:op "analyze-namespaces-stream"
                           :namespaces clj-pairs}
                          (fn [reply]
                            (when (and verbose? (:starting? reply))
                              (binding [*out* *err*]
                                (println (str "[skeptic analyze clj] " (:ns-sym reply)
                                              " (" (:source-file reply) ")"))
                                (flush)))
                            (when-not (:starting? reply)
                              (process-stream-reply clj-state-a clj-failures-a
                                                    ns-entries-a project-disc-a
                                                    shadowed-a loaded-index reply))))
        (when verbose?
          (binding [*out* *err*]
            (println (str "[skeptic analyze clj] stream complete: "
                          (count @clj-state-a) " ok, "
                          (count @clj-failures-a) " load-failures, "
                          (count @shadowed-a) " shadowed"))
            (flush))))
      {:clj-state @clj-state-a
       :clj-load-failures @clj-failures-a
       :ns-entries-map @ns-entries-a
       :project-disc @project-disc-a
       :shadowed-files @shadowed-a})))

(defn- rehydrate-cljs-entry
  "Reshape a cljs analysis entry off the wire into the host's
   `CljsCachedFormEntry`:
   - replay `:source-form-meta` onto `:source-form` and `:ast-form-meta` onto
     the AST nodes' `:form` slots (cljs finding locations come from
     `node-location`, i.e. `(meta (:form node))`). The AST replay walks the
     `:children` spine only — never the full cljs AST.
   - rebuild the `:exception` (a Throwable cannot cross the wire) from the
     worker's `:exception-message` for forms that crashed mid-analysis."
  [{:keys [source-form source-form-meta ast ast-form-meta] :as entry}]
  (let [entry (cond-> (dissoc entry :source-form-meta :ast-form-meta)
                source-form-meta (assoc :source-form
                                        (wire/apply-form-meta source-form source-form-meta))
                ast-form-meta    (assoc :ast (wire/apply-ast-form-meta ast ast-form-meta)))]
    (if (contains? entry :exception-message)
      (let [data-str (:exception-data entry)
            data (when (and (string? data-str) (seq data-str))
                   (try (edn/read-string {:default (fn [_ v] v)} data-str)
                        (catch Throwable e
                          (binding [*out* *err*]
                            (println (str "[skeptic cljs-entry] could not parse :exception-data EDN: "
                                          (.getName (class e)) ": " (.getMessage e)
                                          " — data was " (pr-str (when (string? data-str)
                                                                   (subs data-str 0 (min 200 (count data-str))))))))
                          nil)))]
        (-> entry
            (dissoc :exception-message :exception-data)
            (assoc :ast nil :exception (ex-info (or (:exception-message entry)
                                                    "cljs analyzer error")
                                                (or data {})))))
      entry)))

(defn- fetch-cljs-heads
  "Read each source-file's ns-head on the worker (project basis) for dependency
   ordering. Returns `{:heads {File → ns-head} :head-failures {File → {:exception}}}`.
   A file whose head read throws is recorded as a failure rather than aborting
   the preload, mirroring the analysis-phase containment."
  [conn ns-sym->file]
  (reduce (fn [acc f]
            (try
              (let [{:keys [name requires require-macros use-macros]}
                    (wc/ask conn {:op "cljs-ns-head" :source-file (str f)})]
                (assoc-in acc [:heads f] {:name name
                                          :requires requires
                                          :require-macros require-macros
                                          :use-macros use-macros}))
              (catch Throwable e
                (assoc-in acc [:head-failures f] {:exception e}))))
          {:heads {} :head-failures {}}
          (vals ns-sym->file)))

(defn preload-cljs-state!
  "Parse and analyze every source-file requiring cljs analysis (.cljs or
  .cljc). Files are analyzed in dependency order against a single shared
  cljs compiler state, so each file's `[::namespaces <name>]` entry is
  already present when later files' macros introspect it at expansion
  time (e.g. cljs.test/run-tests calling ana-api/find-ns). Cycles fall
  back to the topo tiebreaker (see `skeptic.cljs.topo`).
  Returns `{:cljs-state {File → {:ns-ast :entries :asts}}
            :cljs-load-failures {File → {:exception Throwable}}}` —
  files whose cljs analyzer-driver pass throws (e.g. malformed ns form,
  parse-ns failure) are recorded in `:cljs-load-failures` rather than
  aborting the preload. Both maps are empty when `cljs-disable?` is
  truthy or `loaded` contains no cljs/cljc sources. The shared state
  is created here and discarded on return; downstream phases consume
  only cached entries."
  [cljs-disable? conn loaded verbose?]
  (if (or cljs-disable? (nil? conn))
    {:cljs-state {} :cljs-load-failures {}}
    (let [ns-sym->file (into {}
                             (comp (filter (fn [[_ _ lang]]
                                             (#{:cljs :both} lang)))
                                   (map (fn [[ns-sym sf _]] [ns-sym sf])))
                             loaded)
          {:keys [heads head-failures]} (fetch-cljs-heads conn ns-sym->file)
          headed       (into {} (filter (fn [[_ f]] (contains? heads f))) ns-sym->file)
          ordered      (topo/topo-sort-files headed (fn [f] (get heads f)))]
      (reduce (fn [acc f]
                (when verbose?
                  (binding [*out* *err*]
                    (println (str "[skeptic analyze cljs] " f))
                    (flush)))
                (try
                  ;; wc/ask returns the raw nREPL reply (carries :id/:session/:status
                  ;; and no :asts). Reshape to the closed SourceFileAnalysis the
                  ;; consumers require, mirroring preload-clj-state!'s :entries pull.
                  (let [{:keys [ns-ast entries]} (wc/ask conn {:op "analyze-cljs-namespace"
                                                               :source-file (str f)})
                        entries (mapv rehydrate-cljs-entry entries)]
                    (update acc :cljs-state assoc f
                            {:ns-ast ns-ast
                             :entries entries
                             :asts (filterv some? (mapv :ast entries))}))
                  (catch Throwable e
                    (update acc :cljs-load-failures assoc f {:exception e}))))
              {:cljs-state {} :cljs-load-failures head-failures}
              ordered))))

(defn project-var-provs
  [opts project-disc ns-entries-map]
  (let [type-override-provs
        (if (:plumatic-disable opts)
          {}
          (into {}
                (map (fn [[sym _]]
                       [sym (prov/make-provenance :type-override sym nil nil [] :clj)]))
                (or (:skeptic/type-overrides opts) {})))]
    (reduce-kv (fn [acc ns-sym discovery-out]
                 (merge acc (ns-var-provs opts ns-sym discovery-out
                                          (get ns-entries-map ns-sym))))
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

  `all-discovered-nss` MUST be the complete discovered namespace map for the
  project, not a subset. Pre-filtering it by `:namespace` / `-n` silently
  drops cross-namespace declarations: call sites in the requested namespace
  that depend on schemas declared elsewhere would fall back to Dyn and
  produce no finding. `-n` is a CHECKING filter and must be applied in
  `skeptic.core/check-project` against the per-ns iteration loop, never
  here.

  When `loaded` includes `.cljs` or `.cljc` source-files, each is read and
  analyzed independently via the cljs analyzer driver and the results are
  carried as a local `cljs-state` map (source-file → `{:ns-ast :entries
  :asts}`) — threaded into per-ns admission/checking and stored on the
  returned `ProjectState`. Per-ns admission/analysis dispatches on the
  per-source-file `lang` carried in each `loaded` triple."
  [opts all-discovered-nss :- copts/DiscoveredNamespaces]
  (binding [class-oracle/*worker-conn* (:worker-conn opts)
            class-oracle/*class-rel-cache* (class-oracle/current-cache)
            class-oracle/*predicate-cache* (class-oracle/current-predicate-cache)]
  (let [{loaded :loaded load-failures :load-failures}
        (preload-namespaces opts all-discovered-nss)
        load-failures (reduce-kv (fn [m k v] (assoc m k (assoc v :phase :load)))
                                 {} load-failures)
        ;; Run cljs analysis before clj namespace preload. CLJ preload requires
        ;; project namespaces and may execute .cljc top-level side effects in the
        ;; worker JVM; cljs macroexpansion must see the project as a cljs build
        ;; would, before those clj-side effects mutate shared runtime state.
        {cljs-state         :cljs-state
         cljs-load-failures :cljs-load-failures}
        (preload-cljs-state! (:cljs-disable opts) (:worker-conn opts) loaded (boolean (:verbose opts)))
        cljs-load-failures (reduce-kv (fn [m k v] (assoc m k (assoc v :phase :cljs-load)))
                                      {} cljs-load-failures)
        ;; Worker clj-state is computed BEFORE discovery/var-provs: discovery and
        ;; admission read the shipped :source-form data, never a host-loaded Var.
        {clj-state         :clj-state
         clj-load-failures :clj-load-failures
         ns-entries-map    :ns-entries-map
         project-disc      :project-disc
         shadowed-files    :shadowed-files}
        (preload-clj-state! (:worker-conn opts) loaded (boolean (:verbose opts)))
        clj-load-failures (reduce-kv (fn [m k v]
                                       (assoc m k (update v :phase #(or % :clj-load))))
                                     {} clj-load-failures)
        ;; A shadowed file's text is not its namespace's definition; admission
        ;; and accessor collection run only over the files the project's own
        ;; load selects.
        loaded (vec (remove (fn [[_ sf _]] (contains? shadowed-files sf)) loaded))
        var-provs (project-var-provs opts project-disc ns-entries-map)
        form-refs (with-meta
                    (reduce (fn [m [_ns-sym d]]
                              (cond-> m d (merge (form-refs-for-ns d var-provs))))
                            {} project-disc)
                    {:aliases-by-ns (into {}
                                           (keep (fn [[ns-sym d]]
                                                   (when d [ns-sym (:aliases d)])))
                                           project-disc)})
        user-fn-summaries (reduce-kv (fn [m _ d] (merge m (collect-user-fn-summaries d)))
                                     {} project-disc)
        verbose? (boolean (:verbose opts))
        {:keys [per-ns-admission admission-failures]}
        (reduce (fn [acc [ns-sym source-file lang]]
                  (when verbose?
                    (binding [*out* *err*]
                      (println (str "[skeptic admission] " ns-sym " (" lang ")"))
                      (flush)))
                  (try
                    (assoc-in acc [:per-ns-admission ns-sym]
                              (typed-decls/namespace-dict opts ns-sym source-file lang
                                                          cljs-state var-provs form-refs
                                                          (get ns-entries-map ns-sym)))
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
             (do
               (when verbose?
                 (binding [*out* *err*]
                   (println (str "[skeptic accessors] " ns-sym))
                   (flush)))
               (try
                 (let [entries (some-> clj-state (get source-file) :entries)
                       asts (->> entries
                                 (remove #(file/is-ns-block? (:source-form %)))
                                 (keep :ast)
                                 vec)]
                   (update acc :accessor-summaries
                           (fn [summaries]
                             (collect-accessor-summaries-for-ns merged-dict ns-sym asts summaries))))
                 (catch Throwable e
                   (assoc-in acc [:accessor-failures ns-sym]
                             {:source-file source-file :exception e :phase :accessors}))))
             acc))
         {:accessor-summaries {} :accessor-failures {}}
         loaded)
        enriched-dict (enrich-conditional-descriptors merged-dict accessor-summaries)
        per-ns (reduce-kv (fn [m k v]
                            (assoc m k (select-keys v [:ignore-body :errors :provenance])))
                          {}
                          per-ns-admission)
        per-ns-failures (merge load-failures admission-failures accessor-failures cljs-load-failures clj-load-failures)]
    (cstate/->ProjectState enriched-dict accessor-summaries per-ns per-ns-failures
                           cljs-state clj-state project-disc var-provs user-fn-summaries
                           shadowed-files (:worker-conn opts)))))

(defn- prepare-namespace
  [project-state ns-sym _source-file]
  (let [per-ns-entry (get-in project-state [:per-ns ns-sym])]
    (when-not (and project-state per-ns-entry)
      (throw (ex-info "prepare-namespace requires project-state with per-ns entry (intake invariant)"
                      {:ns ns-sym :have-project-state? (some? project-state)})))
    (let [{:keys [dict accessor-summaries worker-conn]} project-state
          {:keys [ignore-body errors provenance]} per-ns-entry]
      {:dict dict
       :ignore-body ignore-body
       :accessor-summaries accessor-summaries
       :errors errors
       :provenance provenance
       :worker-conn worker-conn})))

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

(defn- read-pass-results
  [dict ignore-body ns source-file accessor-summaries cljs-state cljs-failure clj-state clj-failure lang form-opts]
  (if (= :cljs lang)
    (cljs-read-pass-results dict ignore-body ns source-file accessor-summaries cljs-state cljs-failure form-opts)
    (clj-read-pass-results dict ignore-body ns source-file accessor-summaries clj-state clj-failure form-opts)))

(s/defn check-ns :- s/Any
  [project-state ns :- s/Symbol source-file form-opts]
  (when-let [{:keys [^Throwable exception]} (get (:per-ns-failures project-state) ns)]
    ;; An ns-keyed failure (admission, accessors) means nothing was admitted
    ;; for this namespace — the passes cannot run. Rethrow the recorded cause;
    ;; `check-namespace` surfaces it as this namespace's exception finding and
    ;; every other namespace still gets full analysis.
    (throw exception))
  (let [{:keys [dict ignore-body accessor-summaries errors provenance]}
        (prepare-namespace project-state ns source-file)
        lang (lang-of-namespace-source form-opts ns source-file)
        passes (case lang :both [:clj :cljs] [lang])
        cljs-state (:cljs-state project-state)
        file-failure (get (:per-ns-failures project-state) source-file)
        cljs-failure (when (= :cljs-load (:phase file-failure))
                       (:exception file-failure))
        clj-failure (when (#{:clj-load :reply-processing} (:phase file-failure))
                      (:exception file-failure))
        clj-state (:clj-state project-state)
        pass-results (binding [ac/*user-fn-path-predicate-summaries*
                               (or (:user-fn-summaries project-state) {})]
                       (mapv (fn [pass-lang]
                               ;; The read pass consumes worker-shipped ASTs via
                               ;; clj-state/cljs-state; no host project-ns binding.
                               (read-pass-results dict ignore-body ns source-file
                                                  accessor-summaries cljs-state cljs-failure
                                                  clj-state clj-failure pass-lang
                                                  form-opts))
                             passes))
        form-findings (vec (mapcat :results pass-results))
        deduped (if (= :both lang)
                  (dedup-cljc-findings form-findings)
                  form-findings)
        merged-prov (-> (reduce (fn [m r] (merge m (:provenance r)))
                                {}
                                pass-results)
                        (merge (or provenance {})))]
    {:results (vec (concat (vec errors) deduped))
     :provenance merged-prov}))

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
  [project-state ns-sym :- s/Symbol source-file form-opts]
  (binding [class-oracle/*worker-conn* (:worker-conn project-state)
            class-oracle/*class-rel-cache* (class-oracle/current-cache)
            class-oracle/*predicate-cache* (class-oracle/current-predicate-cache)]
    (try
      (check-ns project-state ns-sym source-file form-opts)
      (catch Exception e
        {:results [(load-exception-result ns-sym e)]
         :provenance {}}))))
