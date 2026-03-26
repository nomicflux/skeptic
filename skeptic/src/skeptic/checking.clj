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
  (or (instance? Schema schema)
      (class? schema)
      (and (coll? schema) (every? valid-schema? schema))))

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

(defn plain-def-symbol?
  [sym]
  (and (symbol? sym)
       (= "def" (name sym))
       (let [ns-name (namespace sym)]
         (or (nil? ns-name)
             (#{"clojure.core"} ns-name)))))

(defn def-form-symbol
  [form]
  (when (and (seq? form)
             (plain-def-symbol? (first form))
             (symbol? (second form)))
    (second form)))

(defn argvec->arglist-entry
  [argvec]
  (loop [[x & more] argvec
         fixed []
         vararg nil]
    (cond
      (nil? x)
      (if vararg
        [:varargs {:count (inc (count fixed))
                   :arglist (vec (concat fixed [vararg]))}]
        [(count fixed) {:arglist (vec fixed)}])

      (= x '&)
      (recur (next more) fixed (first more))

      :else
      (recur more (conj fixed x) vararg))))

(defn source-derived-arglists
  [form]
  (when-let [decls (seq (defn-decls form))]
    (into {}
          (map (comp argvec->arglist-entry first))
          decls)))

(defn placeholder-ref
  [kind sym]
  [kind sym])

(defn placeholder-entry
  [qualified-sym base source-arglists]
  (let [arglists (or (:arglists base)
                     source-arglists)
        schema-ref (as/placeholder-schema (placeholder-ref :schema qualified-sym))
        output-ref (as/placeholder-schema (placeholder-ref :output qualified-sym))]
    (cond-> {:name (or (:name base) (str qualified-sym))}
      (some? arglists) (assoc :arglists arglists)
      true (assoc :schema (or (:schema base) schema-ref))
      true (assoc :output (or (:output base) output-ref)))))

(defn source-derived-entry
  [dict ns-sym form]
  (let [raw-name (or (some-> form def-form-symbol)
                     (some-> form second ((fn [x]
                                           (when (and (seq? form)
                                                      (or (= 'defn (first form))
                                                          (schema-defn-symbol? (first form)))
                                                      (symbol? x))
                                             x)))))
        qualified-name (when raw-name (qualify-symbol ns-sym raw-name))
        existing (when qualified-name
                   (dict-entry dict ns-sym raw-name))
        source-arglists (source-derived-arglists form)]
    (when qualified-name
      (let [entry (placeholder-entry qualified-name existing source-arglists)]
        {qualified-name entry
         raw-name entry}))))

(defn source-derived-placeholder-dict
  [dict ns-sym exprs]
  (reduce (fn [acc form]
            (merge acc (or (source-derived-entry dict ns-sym form) {})))
          dict
          exprs))

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
       (into {}
             (remove (comp nil? val))
             {:schema (:schema value-node)
              :output (:output value-node)
              :arglists (:arglists value-node)
              :arglist (:arglist value-node)})])))

(defn resolve-entry-placeholders
  [entry resolve-schema]
  (cond
    (nil? entry) nil
    (not (map? entry)) entry
    :else
    (cond-> entry
      (contains? entry :schema) (update :schema resolve-schema)
      (contains? entry :output) (update :output resolve-schema)
      (contains? entry :arglist) (update :arglist #(mapv resolve-schema %))
      (contains? entry :expected-arglist) (update :expected-arglist #(mapv resolve-schema %))
      (contains? entry :actual-arglist) (update :actual-arglist #(mapv resolve-schema %))
      (contains? entry :fn-schema) (update :fn-schema resolve-schema)
      (contains? entry :locals) (update :locals (fn [locals]
                                                  (into {}
                                                        (map (fn [[k v]]
                                                               [k (resolve-entry-placeholders v resolve-schema)]))
                                                        locals)))
      (contains? entry :arglists) (update :arglists (fn [arglists]
                                                      (into {}
                                                            (map (fn [[k v]]
                                                                   [k (resolve-entry-placeholders v resolve-schema)]))
                                                            arglists)))
      (contains? entry :arg-schema) (update :arg-schema #(mapv (fn [arg]
                                                                 (resolve-entry-placeholders arg resolve-schema))
                                                               %))
      (contains? entry :params) (update :params #(mapv (fn [param]
                                                         (resolve-entry-placeholders param resolve-schema))
                                                       %)))))

(declare resolve-analyzed-node)

(defn resolve-analyzed-node
  [node resolve-schema]
  (if (map? node)
    (let [resolved-children (reduce (fn [acc child-key]
                                      (let [value (get acc child-key)
                                            resolved (cond
                                                       (vector? value) (mapv #(resolve-analyzed-node % resolve-schema) value)
                                                       (map? value) (resolve-analyzed-node value resolve-schema)
                                                       :else value)]
                                        (assoc acc child-key resolved)))
                                    node
                                    (:children node))
          resolved-entry (resolve-entry-placeholders resolved-children resolve-schema)
          resolved-entry (cond-> resolved-entry
                           (vector? (:args resolved-entry))
                           (assoc :actual-arglist (mapv :schema (:args resolved-entry))))
          resolved-entry (if (= :invoke (:op resolved-entry))
                           (let [{:keys [expected-arglist fn-schema]}
                                 (analysis/call-info (:fn resolved-entry) (:args resolved-entry))]
                             (cond-> (assoc resolved-entry
                                            :expected-arglist (mapv as/canonicalize-schema expected-arglist))
                               fn-schema (assoc :fn-schema (as/canonicalize-schema fn-schema))))
                           resolved-entry)]
      (cond
        (and (= :static-call (:op resolved-entry))
             (analysis/static-get-call? resolved-entry))
        (let [[target key-node default-node] (:args resolved-entry)
              key-schema (as/valued-schema (:schema key-node) (:form key-node))
              schema (if default-node
                       (as/map-get-schema (:schema target)
                                          key-schema
                                          (:schema default-node))
                       (as/map-get-schema (:schema target)
                                          key-schema))]
          (assoc resolved-entry :schema (as/canonicalize-schema schema)))

        (and (= :static-call (:op resolved-entry))
             (analysis/static-merge-call? resolved-entry))
        (assoc resolved-entry
               :schema (as/canonicalize-schema
                        (as/merge-map-schemas (map :schema (:args resolved-entry)))))

        (and (= :invoke (:op resolved-entry))
             (analysis/get-call? (:fn resolved-entry)))
        (let [[target key-node default-node] (:args resolved-entry)
              key-schema (as/valued-schema (:schema key-node) (:form key-node))
              schema (if default-node
                       (as/map-get-schema (:schema target)
                                          key-schema
                                          (:schema default-node))
                       (as/map-get-schema (:schema target)
                                          key-schema))]
          (assoc resolved-entry :schema (as/canonicalize-schema schema)))

        (and (= :invoke (:op resolved-entry))
             (analysis/merge-call? (:fn resolved-entry)))
        (assoc resolved-entry
               :schema (as/canonicalize-schema
                        (as/merge-map-schemas (map :schema (:args resolved-entry)))))

        :else
        resolved-entry))
    node))

(defn namespace-placeholder-resolver
  [entries]
  (let [cache (atom {})
        resolving ::resolving]
    (letfn [(resolve-entry* [qualified-sym]
              (let [cached (get @cache qualified-sym ::missing)]
                (cond
                  (= cached resolving) {:schema s/Any
                                        :output s/Any}
                  (not= cached ::missing) cached
                  :else
                  (do
                    (swap! cache assoc qualified-sym resolving)
                    (let [entry (get entries qualified-sym)
                          resolved (when entry
                                     (resolve-entry-placeholders
                                      entry
                                      resolve-schema*))]
                      (swap! cache assoc qualified-sym resolved)
                      resolved)))))
            (resolve-placeholder* [[kind qualified-sym]]
              (let [entry (resolve-entry* qualified-sym)]
                (case kind
                  :schema (:schema entry)
                  :output (or (:output entry) (:schema entry))
                  nil)))
            (resolve-schema* [schema]
              (as/resolve-placeholders schema resolve-placeholder*))]
      resolve-schema*)))

(defn analyze-source-exprs
  [dict ns-sym source-file exprs]
  (let [analysis-dict (source-derived-placeholder-dict dict ns-sym exprs)
        analyzed (mapv (fn [expr]
                         (analysis/attach-schema-info-loop analysis-dict
                                                            (normalize-check-form expr)
                                                            {:ns ns-sym
                                                             :source-file (source-file-path source-file)}))
                       exprs)
        analyzed-entries (into {}
                              (keep #(analyzed-def-entry ns-sym %))
                              analyzed)
        resolve-schema (namespace-placeholder-resolver analyzed-entries)
        resolved (mapv #(resolve-analyzed-node % resolve-schema) analyzed)]
    {:analysis-dict analysis-dict
     :analyzed analyzed
     :resolved resolved
     :resolved-defs (into {}
                         (keep #(analyzed-def-entry ns-sym %))
                         resolved)}))

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
                     (when-let [error (inconsistence/mismatched-output-schema
                                       {:expr (:name node)
                                        :arg (:expr display)}
                                       expected-output
                                       actual-output)]
                       {:blame (:expr display)
                        :source-expression (:source-expression display)
                        :expanded-expression (:expanded-expression display)
                        :location (:location display)
                        :enclosing-form enclosing-form
                        :path nil
                        :context {:local-vars (local-vars-context bindings body)
                                  :refs (if (call-node? body)
                                          (call-refs bindings body)
                                          [])}
                        :errors [error]}))))))))

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
                                       errors (vec (inconsistence/inconsistent? (:expr display)
                                                                                arg-expr
                                                                                expected
                                                                                actual))]
                                   (when (seq errors)
                                     {:focus arg-expr
                                      :focus-source (:source-expression arg-display)
                                      :errors errors})))
                               matched)
            errors (vec (mapcat :errors error-groups))]
        {:blame (:expr display)
         :source-expression (:source-expression display)
         :expanded-expression (:expanded-expression display)
         :location (:location display)
         :enclosing-form enclosing-form
         :focuses (distinctv (keep :focus error-groups))
         :focus-sources (distinctv (keep :focus-source error-groups))
         :path nil
         :context {:local-vars (local-vars-context bindings node)
                   :refs (call-refs bindings node)}
         :errors errors}))))

(defn check-s-expr
  [dict s-expr {:keys [keep-empty remove-context ns source-file] :as opts}]
  (try
    (let [normalized (normalize-check-form s-expr)
          enclosing-form (if (and (seq? s-expr)
                                  (symbol? (second s-expr))
                                  (symbol? (first s-expr)))
                           (qualify-symbol ns (second s-expr))
                           s-expr)
          analysed (analysis/attach-schema-info-loop dict
                                                     normalized
                                                     (assoc opts :source-file (source-file-path source-file)))
          bindings (binding-index analysed)]
      (cond->> (->> (ast-nodes-preorder analysed)
                    (mapcat (fn [node]
                              (concat (when-let [call-result (match-s-exprs bindings
                                                                           enclosing-form
                                                                           node)]
                                        [call-result])
                                      (or (def-output-results dict
                                                              bindings
                                                              ns
                                                              s-expr
                                                              enclosing-form
                                                              node)
                                          [])))))
        (not keep-empty)
        (remove (comp empty? :errors))

        remove-context
        (map #(dissoc % :context))))
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
          {:keys [resolved]} (analyze-source-exprs dict ns source-file exprs)
          bindings (mapv binding-index resolved)
          opts (assoc opts
                      :ns ns
                      :source-file source-file)]
      (mapcat (fn [source-form analyzed bindings]
                (cond->> (->> (ast-nodes-preorder analyzed)
                              (mapcat (fn [node]
                                        (concat (when-let [call-result (match-s-exprs bindings
                                                                                     (if (and (seq? source-form)
                                                                                              (symbol? (second source-form))
                                                                                              (symbol? (first source-form)))
                                                                                       (qualify-symbol ns (second source-form))
                                                                                       source-form)
                                                                                     node)]
                                                  [call-result])
                                                (or (def-output-results dict
                                                                        bindings
                                                                        ns
                                                                        source-form
                                                                        (if (and (seq? source-form)
                                                                                 (symbol? (second source-form))
                                                                                 (symbol? (first source-form)))
                                                                          (qualify-symbol ns (second source-form))
                                                                          source-form)
                                                                        node)
                                                    [])))))
                  (not (:keep-empty opts))
                  (remove (comp empty? :errors))

                  (:remove-context opts)
                  (map #(dissoc % :context))))
              exprs
              resolved
              bindings))))
