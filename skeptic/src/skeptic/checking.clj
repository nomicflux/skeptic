(ns skeptic.checking
  (:require [clojure.tools.analyzer.ast :as ana.ast]
            [schema.core :as s]
            [skeptic.analysis :as analysis]
            [skeptic.analysis.schema :as as]
            [skeptic.file :as file]
            [skeptic.inconsistence :as inconsistence])
  (:import [java.io File]
           [schema.core Schema]))

(def spy-on false)
(def spy-only #{})

(defn spy*
  [msg x]
  (when (and spy-on (or (nil? spy-only)
                        (contains? spy-only msg)))
    (try (println msg (pr-str x))
         (catch Exception e
           (println msg e))))
  x)

(defmacro spy
  [msg x]
  #_
  `(spy* ~msg ~x)
  x)

(defn valid-schema?
  [schema]
  (boolean (as/schema? schema)))

(defmacro assert-schema
  [schema]
  #_
  `(do (assert (valid-schema? ~schema) (format "Must be valid schema: %s" ~schema))
       ~schema)
  schema)

(defmacro assert-has-schema
  [x]
  #_
  `(do (assert (valid-schema? (:schema ~x)) (format "Must be valid schema: %s (%s)" (:schema ~x) (pr-str ~x)))
       ~x)
  x)

(def invoke-ops
  #{:instance-call
    :invoke
    :keyword-invoke
    :prim-invoke
    :protocol-invoke
    :static-call})

(defn with-form-meta
  [original rewritten]
  (if (instance? clojure.lang.IObj rewritten)
    (with-meta rewritten (meta original))
    rewritten))

(defn schema-defn-symbol?
  [sym]
  (and (symbol? sym)
       (= "defn" (name sym))
       (#{"s" "schema.core"} (namespace sym))))

(defn strip-schema-argvec
  [argvec]
  (with-form-meta
    argvec
    (loop [[x & more] argvec
           acc []]
      (cond
        (nil? x) (vec acc)
        (= x ':-) (recur (next more) acc)
        :else (recur more (conj acc x))))))

(defn strip-schema-method
  [decl]
  (let [[args & body] decl]
    (with-form-meta decl
      (list* (strip-schema-argvec args) body))))

(defn strip-schema-defn
  [form]
  (let [[_defn-sym name & more] form
        [more] (if (= ':- (first more))
                 [(nnext more)]
                 [more])
        [docstring more] (if (string? (first more))
                           [(first more) (next more)]
                           [nil more])
        [attr-map more] (if (map? (first more))
                          [(first more) (next more)]
                          [nil more])
        decls (if (vector? (first more))
                [(with-form-meta (first more)
                   (list* (strip-schema-argvec (first more)) (next more)))]
                (map strip-schema-method more))]
    (with-form-meta form
      (list* 'defn
             name
             (concat (when docstring [docstring])
                     (when attr-map [attr-map])
                     decls)))))

(defn normalize-check-form
  [form]
  (if (and (seq? form) (schema-defn-symbol? (first form)))
    (strip-schema-defn form)
    form))

(defn source-file-path
  [source-file]
  (cond
    (nil? source-file) nil
    (instance? File source-file) (.getPath ^File source-file)
    :else (str source-file)))

(defn merge-location
  [& locations]
  (when-let [present (seq (remove nil? locations))]
    (reduce (fn [acc location]
              (merge acc (into {}
                               (remove (comp nil? val))
                               location)))
            {}
            present)))

(defn form-location
  [source-file form]
  (merge-location {:file (source-file-path source-file)}
                  (select-keys (meta form) [:line :column :end-line :end-column])))

(defn form-source
  [form]
  (:source (meta form)))

(defn defn-decls
  [form]
  (when (and (seq? form)
             (symbol? (first form))
             (or (= 'defn (first form))
                 (schema-defn-symbol? (first form))))
    (let [[head _name & more] form
          more (if (and (schema-defn-symbol? head)
                        (= ':- (first more)))
                 (nnext more)
                 more)
          more (if (string? (first more))
                 (next more)
                 more)
          more (if (map? (first more))
                 (next more)
                 more)]
      (if (vector? (first more))
        [(with-form-meta (first more)
           (list* (first more) (next more)))]
        more))))

(defn method-source-body
  [decl]
  (let [[_args & body] decl]
    (cond
      (empty? body) nil
      (= 1 (count body)) (first body)
      :else (with-form-meta (first body)
              (list* 'do body)))))

(defn node-location
  [node]
  (select-keys (meta (:form node)) [:file :line :column :end-line :end-column]))

(defn display-expr
  [node]
  (let [expr (:form node)
        source-expression (form-source expr)]
    {:expr expr
     :source-expression source-expression
     :expanded-expression (when (and source-expression
                                     (not= source-expression (pr-str expr)))
                            expr)
     :location (node-location node)}))

(defn distinctv
  [xs]
  (reduce (fn [acc x]
            (if (some #(= % x) acc)
              acc
              (conj acc x)))
          []
          xs))

(defn child-nodes
  [node]
  (mapcat (fn [child]
            (let [value (get node child)]
              (cond
                (vector? value) value
                (map? value) [value]
                :else [])))
          (:children node)))

(defn ast-nodes-preorder
  [ast]
  (tree-seq map? child-nodes ast))

(defn node-ref
  [node]
  (when node
    (select-keys node [:form :schema])))

(defn callee-ref
  [node]
  (when node
    (case (:op node)
      :invoke (node-ref (:fn node))
      :with-meta (recur (:expr node))
      nil)))

(defn match-up-arglists
  [arg-nodes expected actual]
  (spy :match-up-actual-list actual)
  (spy :match-up-expected-list expected)
  (let [size (max (count expected) (count actual))
        expected-vararg (last expected)]
    (for [n (range 0 size)]
      [(get arg-nodes n)
       (spy :match-up-expected (get expected n expected-vararg))
       (spy :match-up-actual (get actual n))])))

(defn binding-index
  [ast]
  (reduce (fn [acc node]
            (if (= :binding (:op node))
              (assoc acc (:form node) node)
              acc))
          {}
          (ana.ast/nodes ast)))

(declare local-resolution-path)

(defn local-resolution-path
  [bindings local-node]
  (if-let [binding (get bindings (:form local-node))]
    (if-let [init (:init binding)]
      (cond-> [(node-ref init)]
        (callee-ref init)
        (conj (callee-ref init)))
      [])
    []))

(defn local-vars-context
  [bindings node]
  (->> (ana.ast/nodes node)
       (filter #(= :local (:op %)))
       (reduce (fn [acc local-node]
                 (if (contains? acc (:form local-node))
                   acc
                   (assoc acc
                          (:form local-node)
                          {:form (:form local-node)
                           :schema (:schema local-node)
                           :resolution-path (local-resolution-path bindings local-node)})))
               {})))

(defn call-refs
  [bindings node]
  (let [fn-node (:fn node)]
    (cond
      (nil? fn-node) []
      (= :local (:op fn-node))
      (into [(node-ref fn-node)]
            (local-resolution-path bindings fn-node))
      :else
      (cond-> []
        (node-ref fn-node)
        (conj (node-ref fn-node))))))

(defn call-node?
  [node]
  (and (contains? invoke-ops (:op node))
       (vector? (:args node))
       (seq (:expected-arglist node))
       (seq (:actual-arglist node))))

(defn qualify-symbol
  [ns-sym sym]
  (cond
    (nil? sym) nil
    (not (symbol? sym)) sym
    (namespace sym) sym
    ns-sym (symbol (str ns-sym "/" sym))
    :else sym))

(defn dict-entry
  [dict ns-sym sym]
  (or (get dict sym)
      (get dict (qualify-symbol ns-sym sym))))

(defn unwrap-with-meta
  [node]
  (if (= :with-meta (:op node))
    (recur (:expr node))
    node))

(defn analyzed-def-entry
  [ns-sym analyzed]
  (let [node (unwrap-with-meta analyzed)
        init-node (some-> node :init unwrap-with-meta)
        value-node (or (some-> init-node :expr unwrap-with-meta)
                       init-node)
        raw-name (some-> (:name node) name symbol)
        qualified-name (when raw-name
                         (qualify-symbol ns-sym raw-name))]
    (when (and (= :def (:op node))
               qualified-name
               value-node)
      [qualified-name
       (as/strip-derived-types
        (into {}
              (remove (comp nil? val))
              {:schema (:schema value-node)
               :output (:output value-node)
               :arglists (:arglists value-node)
               :arglist (:arglist value-node)}))])))

(defn analyze-source-exprs
  [dict ns-sym source-file exprs]
  (let [analysis-dict dict
        analyzed (mapv (fn [expr]
                         (analysis/attach-schema-info-loop analysis-dict
                                                            (normalize-check-form expr)
                                                            {:ns ns-sym
                                                             :source-file (source-file-path source-file)}))
                       exprs)]
    {:analysis-dict analysis-dict
     :analyzed analyzed
     :resolved analyzed
     :resolved-defs (into {}
                         (keep #(analyzed-def-entry ns-sym %))
                         analyzed)}))

(defn method-output-schema
  [method]
  (let [body (:body method)
        output (:output method)
        tagged-output (some-> (:tag body) analysis/class->schema)]
    (if (inconsistence/unknown-output-schema? output)
      (as/canonicalize-schema (or tagged-output output))
      (as/canonicalize-schema output))))

(defn def-output-results
  [dict bindings ns-sym source-form enclosing-form node]
  (let [entry (dict-entry dict ns-sym (:name node))
        expected-output (some-> (:output entry) as/canonicalize-schema)
        init-node (some-> node :init unwrap-with-meta)
        methods (:methods init-node)
        source-bodies (map method-source-body (defn-decls source-form))]
    (when (and expected-output (seq methods))
      (->> (map vector methods source-bodies)
           (keep (fn [[method source-body]]
                   (let [actual-output (method-output-schema method)
                         body (:body method)
                         source-body-location (when source-body
                                                (select-keys (meta source-body)
                                                             [:file :line :column :end-line :end-column]))
                         source-expression (form-source source-body)
                         display {:expr (or source-body (:form body))
                                  :source-expression source-expression
                                  :expanded-expression (when (or (not= source-body (:form body))
                                                                 (and source-expression
                                                                      (not= source-expression (pr-str (:form body)))))
                                                         (:form body))
                                  :location source-body-location}]
                     (let [report (inconsistence/output-cast-report
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
                          :context {:local-vars (local-vars-context bindings body)
                                    :refs (if (call-node? body)
                                            (call-refs bindings body)
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
  (when (call-node? node)
    (let [expected-arglist (vec (:expected-arglist node))
          actual-arglist (vec (:actual-arglist node))
          display (display-expr node)]
      (assert (not (or (nil? expected-arglist) (nil? actual-arglist)))
              (format "Arglists must not be nil: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (assert (>= (count actual-arglist) (count expected-arglist))
              (format "Actual should have at least as many elements as expected: %s %s\n%s"
                      expected-arglist actual-arglist node))
      (let [matched (spy :matched-arglists (match-up-arglists (:args node)
                                                              (spy :expected-arglist expected-arglist)
                                                              (spy :actual-arglist actual-arglist)))
            error-groups (keep (fn [[arg-node expected actual]]
                                 (let [arg-display (when arg-node
                                                     (display-expr arg-node))
                                       arg-expr (or (:expr arg-display)
                                                    (:form arg-node))
                                       report (inconsistence/cast-report
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
         :focuses (distinctv (keep :focus error-groups))
         :focus-sources (distinctv (keep :focus-source error-groups))
         :path nil
         :blame-side (or (:blame-side primary-group) :none)
         :blame-polarity (or (:blame-polarity primary-group) :none)
         :rule (:rule primary-group)
         :expected-type (:expected-type primary-group)
         :actual-type (:actual-type primary-group)
         :cast-result (:cast-result primary-group)
         :cast-results (vec (mapcat :cast-results error-groups))
         :context {:local-vars (local-vars-context bindings node)
                   :refs (call-refs bindings node)}
         :errors errors}))))

(defn enclosing-form
  [ns-sym source-form]
  (if (and (seq? source-form)
           (symbol? (second source-form))
           (symbol? (first source-form)))
    (qualify-symbol ns-sym (second source-form))
    source-form))

(defn check-resolved-form
  [dict ns-sym source-form analyzed {:keys [keep-empty remove-context]}]
  (let [bindings (binding-index analyzed)
        enclosing-form (enclosing-form ns-sym source-form)]
    (cond->> (->> (ast-nodes-preorder analyzed)
                  (mapcat (fn [node]
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
                                        [])))))
      (not keep-empty)
      (remove (comp empty? :errors))

      remove-context
      (map #(dissoc % :context)))))

(declare ns-exprs)

(defn check-s-expr
  [dict s-expr {:keys [keep-empty remove-context ns source-file] :as opts}]
  (try
    (binding [*ns* (the-ns ns)]
      (let [source-form (normalize-check-form s-expr)
            exprs (if source-file
                    (ns-exprs source-file)
                    [source-form])
            {:keys [resolved]} (analyze-source-exprs dict ns source-file exprs)
            expr-idx (or (first (keep-indexed (fn [idx expr]
                                                (when (= source-form
                                                         (normalize-check-form expr))
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

(defn ns-exprs
  [source-file]
  (with-open [reader (file/pushback-reader source-file)]
    (->> (repeatedly #(file/try-read reader))
         (take-while some?)
         (remove file/is-ns-block?)
         doall)))

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
