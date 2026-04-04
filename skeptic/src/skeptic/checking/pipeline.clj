(ns skeptic.checking.pipeline
  (:require [skeptic.analysis.annotate :as aa]
            [skeptic.analysis.calls :as ac]
            [skeptic.analysis.normalize :as an]
            [skeptic.analysis.type-ops :as ato]
            [skeptic.analysis.value :as av]
            [skeptic.analysis.bridge.localize :as abl]
            [skeptic.analysis.bridge.render :as abr]
            [skeptic.checking.ast :as ca]
            [skeptic.checking.form :as cf]
            [skeptic.file :as file]
            [skeptic.inconsistence.mismatch :as incm]
            [skeptic.inconsistence.report :as inrep])
  (:import [java.io File]))

(defn analyzed-def-entry
  [ns-sym analyzed]
  (let [node (ca/unwrap-with-meta analyzed)
        init-node (some-> node :init ca/unwrap-with-meta)
        value-node (or (some-> init-node :expr ca/unwrap-with-meta)
                       init-node)
        raw-name (some-> (:name node) name symbol)
        qualified-name (when raw-name
                         (ac/qualify-symbol ns-sym raw-name))]
    (when (and (= :def (:op node))
               qualified-name
               value-node)
      [qualified-name
       (abr/strip-derived-types
        (into {}
              (remove (comp nil? val))
              {:type (:type value-node)
               :output-type (:output-type value-node)
               :arglists (:arglists value-node)
               :arglist (:arglist value-node)}))])))

(defn analyze-source-exprs
  [dict ns-sym source-file exprs]
  (let [analysis-dict dict
        analyzed (mapv (fn [expr]
                         (aa/annotate-form-loop analysis-dict
                                                (cf/normalize-check-form expr)
                                                {:ns ns-sym
                                                 :source-file (cf/source-file-path source-file)}))
                       exprs)]
    {:analysis-dict analysis-dict
     :analyzed analyzed
     :resolved analyzed
     :resolved-defs (into {}
                          (keep #(analyzed-def-entry ns-sym %))
                          analyzed)}))

(defn method-output-type
  [method]
  (let [body (:body method)
        output-type (:output-type method)
        tagged-output (some-> (:tag body) av/class->type)]
    (if (incm/unknown-output-type? (ato/normalize-type output-type))
      (ato/normalize-type (or tagged-output output-type))
      (ato/normalize-type output-type))))

(defn def-output-results
  [dict bindings ns-sym source-form enclosing-form node]
  (let [entry (some-> (ca/dict-entry dict ns-sym (:name node))
                      an/normalize-entry)
        expected-output (some-> (:output-type entry) ato/normalize-type)
        init-node (some-> node :init ca/unwrap-with-meta)
        methods (:methods init-node)
        source-bodies (map cf/method-source-body (cf/defn-decls source-form))]
    (when (and expected-output (seq methods))
      (->> (map vector methods source-bodies)
           (keep (fn [[method source-body]]
                   (let [actual-output (method-output-type method)
                         body (:body method)
                         source-body-location (when source-body
                                                (select-keys (meta source-body)
                                                             [:file :line :column :end-line :end-column]))
                         source-expression (cf/form-source source-body)
                         display {:expr (or source-body (:form body))
                                  :source-expression source-expression
                                  :expanded-expression (when (or (not= source-body (:form body))
                                                                 (and source-expression
                                                                      (not= source-expression (pr-str (:form body)))))
                                                         (:form body))
                                  :location source-body-location}]
                     (let [report (inrep/output-cast-report
                                   {:expr (:name node)
                                    :arg (:expr display)}
                                   expected-output
                                   actual-output)]
                       (when-not (:ok? report)
                         {:blame (:expr display)
                          :report-kind :output
                          :source-expression (:source-expression display)
                          :expanded-expression (:expanded-expression display)
                          :location (:location display)
                          :enclosing-form enclosing-form
                          :path nil
                          :context {:local-vars (ca/local-vars-context bindings body)
                                    :refs (if (ca/call-node? body)
                                            (ca/call-refs bindings body)
                                            [])}
                          :blame-side (:blame-side report)
                          :blame-polarity (:blame-polarity report)
                          :rule (:rule report)
                          :expected-type (:expected-type report)
                          :actual-type (:actual-type report)
                          :cast-result (:cast-result report)
                          :cast-results (:cast-results report)
                          :errors (:errors report)})))))))))

(defn match-s-exprs
  [bindings enclosing-form node]
  (when (ca/call-node? node)
    (let [expected-arglist (vec (:expected-argtypes node))
          actual-arglist (vec (:actual-argtypes node))
          display (cf/display-expr node)]
      (assert (not (or (nil? expected-arglist) (nil? actual-arglist)))
              (format "Arglists must not be nil: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (assert (>= (count actual-arglist) (count expected-arglist))
              (format "Actual should have at least as many elements as expected: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (let [matched (cf/spy :matched-arglists (ca/match-up-arglists (:args node)
                                                                  (cf/spy :expected-argtypes expected-arglist)
                                                                  (cf/spy :actual-argtypes actual-arglist)))
            error-groups (keep (fn [[arg-node expected actual]]
                                 (let [arg-display (when arg-node
                                                     (cf/display-expr arg-node))
                                       arg-expr (or (:expr arg-display)
                                                    (:form arg-node))
                                       report (inrep/cast-report
                                               {:expr (:expr display)
                                                :arg arg-expr}
                                               expected
                                               actual)]
                                   (when (seq (:errors report))
                                     {:focus arg-expr
                                      :focus-source (:source-expression arg-display)
                                      :blame-side (:blame-side report)
                                      :blame-polarity (:blame-polarity report)
                                      :rule (:rule report)
                                      :expected-type (:expected-type report)
                                      :actual-type (:actual-type report)
                                      :cast-result (:cast-result report)
                                      :cast-results (:cast-results report)
                                      :errors (:errors report)})))
                               matched)
            primary-group (first error-groups)
            errors (vec (mapcat :errors error-groups))]
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
         :cast-result (:cast-result primary-group)
         :cast-results (vec (mapcat :cast-results error-groups))
         :context {:local-vars (ca/local-vars-context bindings node)
                   :refs (ca/call-refs bindings node)}
         :errors errors}))))

(defn enclosing-form
  [ns-sym source-form]
  (if (and (seq? source-form)
           (symbol? (second source-form))
           (symbol? (first source-form)))
    (ac/qualify-symbol ns-sym (second source-form))
    source-form))

(defn check-resolved-form
  [dict ns-sym source-form analyzed {:keys [keep-empty remove-context]}]
  (let [bindings (ca/binding-index analyzed)
        enclosing-form (enclosing-form ns-sym source-form)]
    (cond->> (->> (ca/ast-nodes-preorder analyzed)
                  (mapcat (fn [node]
                            (abl/with-error-context (cf/node-error-context node enclosing-form)
                              (doall
                               (concat (when-let [call-result (match-s-exprs bindings
                                                                            enclosing-form
                                                                            node)]
                                         [call-result])
                                       (or (def-output-results dict
                                                               bindings
                                                               ns-sym
                                                               source-form
                                                               enclosing-form
                                                               node)
                                           [])))))))
      (not keep-empty)
      (remove (comp empty? :errors))

      remove-context
      (map #(dissoc % :context)))))

(defn ns-exprs
  [source-file]
  (with-open [reader (file/pushback-reader source-file)]
    (->> (repeatedly #(file/try-read reader))
         (take-while some?)
         (remove file/is-ns-block?)
         doall)))

(defn check-s-expr
  [dict s-expr {:keys [keep-empty remove-context ns source-file]}]
  (try
    (binding [*ns* (the-ns ns)]
      (let [source-form (cf/normalize-check-form s-expr)
            exprs (if source-file
                    (ns-exprs source-file)
                    [source-form])
            {:keys [resolved]} (analyze-source-exprs dict ns source-file exprs)
            expr-idx (or (first (keep-indexed (fn [idx expr]
                                                (when (= source-form
                                                         (cf/normalize-check-form expr))
                                                  idx))
                                              exprs))
                         0)]
        (check-resolved-form dict
                             ns
                             (nth exprs expr-idx)
                             (nth resolved expr-idx)
                             {:keep-empty keep-empty
                              :remove-context remove-context})))
    (catch Exception e
      (println "Error parsing expression")
      (println (pr-str s-expr))
      (println e)
      (throw e))))

(defmacro block-in-ns
  [ns ^File file & body]
  `(let [contents# (slurp ~file)
         ns-dec# (read-string contents#)
         current-namespace# (str ~*ns*)]
     (eval ns-dec#)
     (let [res# (do ~@body)]
       (clojure.core/in-ns (symbol current-namespace#))
       res#)))

;; TODO: if unparseable, throws error
;; Should either pass that on, or (ideally) localize it to a single s-expr and flag that
(defn check-ns
  [dict ns source-file opts]
  (binding [*ns* (the-ns ns)]
    (let [exprs (ns-exprs source-file)
          {:keys [resolved]} (analyze-source-exprs dict ns source-file exprs)]
      (mapcat (fn [source-form analyzed]
                (check-resolved-form dict ns source-form analyzed opts))
              exprs
              resolved))))
