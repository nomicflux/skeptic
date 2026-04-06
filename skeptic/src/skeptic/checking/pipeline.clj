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
            [skeptic.inconsistence.report :as inrep]
            [skeptic.typed-decls :as typed-decls])
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
                         body (:body method)]
                     (let [report (inrep/output-cast-report
                                   {:expr (:name node)
                                    :arg (or source-body (:form body))}
                                   expected-output
                                   actual-output)]
                       (when-not (:ok? report)
                         (let [source-body-location (when source-body
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
                           :errors (:errors report)}))))))))))

(defn input-error-group
  [expr arg-node expected actual]
  (let [arg-expr (if (some? arg-node)
                   (:form arg-node)
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
         :cast-result (:cast-result report)
         :cast-results (:cast-results report)
         :errors (:errors report)}))))

(defn input-cast-result
  [bindings enclosing-form node error-groups]
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
     :cast-result (:cast-result primary-group)
     :cast-results (vec (mapcat :cast-results error-groups))
     :context {:local-vars (ca/local-vars-context bindings node)
               :refs (ca/call-refs bindings node)}
     :errors (vec (mapcat :errors error-groups))}))

(defn match-s-exprs
  [bindings enclosing-form node keep-empty]
  (when (ca/call-node? node)
    (let [expected-arglist (vec (:expected-argtypes node))
          actual-arglist (vec (:actual-argtypes node))]
      (assert (not (or (nil? expected-arglist) (nil? actual-arglist)))
              (format "Arglists must not be nil: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (assert (>= (count actual-arglist) (count expected-arglist))
              (format "Actual should have at least as many elements as expected: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (let [matched (cf/spy :matched-arglists
                            (ca/match-up-arglists (:args node)
                                                  (cf/spy :expected-argtypes expected-arglist)
                                                  (cf/spy :actual-argtypes actual-arglist)))]
        (if-let [error-groups (seq (keep (fn [[arg-node expected actual]]
                                           (input-error-group (:form node) arg-node expected actual))
                                         matched))]
          (input-cast-result bindings enclosing-form node error-groups)
          (when keep-empty
            (input-cast-result bindings enclosing-form node [])))))))

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
        enclosing-form (enclosing-form ns-sym source-form)
        results (->> (ca/ast-nodes-preorder analyzed)
                     (mapcat (fn [node]
                               (abl/with-error-context (cf/node-error-context node enclosing-form)
                                 (let [call-result (match-s-exprs bindings
                                                                  enclosing-form
                                                                  node
                                                                  keep-empty)]
                                   (concat (when call-result
                                             [call-result])
                                           (or (def-output-results dict
                                                                   bindings
                                                                   ns-sym
                                                                   source-form
                                                                   enclosing-form
                                                                   node)
                                               []))))))
                     vec)
        results (cond->> results
                  (not keep-empty)
                  (remove (comp empty? :errors))

                  remove-context
                  (map #(dissoc % :context)))]
    (vec results)))

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
  [dict ns source-file source-form opts]
  (try
    (let [{:keys [resolved]} (analyze-source-exprs dict ns source-file [source-form])]
      (vec (check-resolved-form dict
                                ns
                                source-form
                                (first resolved)
                                opts)))
    (catch Exception e
      [(expression-exception-result ns source-file source-form e)])))

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
    (with-open [reader (file/pushback-reader source-file)]
      (loop [results []]
        (let [{:keys [kind form exception]} (next-checkable-form reader)]
          (case kind
            :eof results
            :form (recur (into results
                               (check-ns-form dict ns source-file form opts)))
            :read-error (conj results (read-exception-result source-file exception))))))))

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
    (require ns-sym)
    (let [{:keys [entries errors]} (typed-decls/typed-ns-results opts ns-sym)
          check-results (check-ns entries ns-sym source-file opts)]
      (vec (concat errors check-results)))
    (catch Exception e
      [(load-exception-result ns-sym e)])))
